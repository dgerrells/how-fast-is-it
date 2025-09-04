package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"log"
	"math"
	"math/rand"
	"net/http"

	// _ "net/http/pprof"
	"runtime"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type Particle struct {
	x  float32
	y  float32
	dx float32
	dy float32
}

type SimState struct {
	dt     float32
	width  uint32
	height uint32
}

type Input struct {
	X           float32
	Y           float32
	IsTouchDown bool
}

type ClientCam struct {
	X      float32
	Y      float32
	Width  int32
	Height int32
}

type Frame struct {
	data []byte
}

type SimJob struct {
	startIndex int
	endIndex   int
	simState   SimState
	inputs     [maxClients]Input
	numClients int
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

// var clientSendChannels [maxClients]chan []byte
var clientSendChannelMap = make(map[*websocket.Conn]chan []byte)

const numBuffers = 3
const maxClients = 100
const particleCount = 3000000

var numThreads = int(math.Min(math.Max(float64(runtime.NumCPU()-1), 1), 4))
var particlesPerThread = particleCount / numThreads

const friction = 0.99

var (
	clients    []*websocket.Conn
	clientsMu  sync.Mutex
	frameCount uint64
	inputs     [maxClients]Input
	cameras    [maxClients]ClientCam
	particles  = []Particle{}
	simState   = SimState{
		dt:     1.0 / 60.0,
		width:  2000,
		height: 2000,
	}
)

func startSim() {
	framePool := sync.Pool{
		New: func() interface{} {
			return &Frame{
				data: make([]byte, int(simState.width*simState.height)/8),
			}
		},
	}
	framesChannel := make(chan *Frame, 3)

	// gen particles
	for i := 0; i < particleCount; i++ {
		particles = append(particles, Particle{
			x:  rand.Float32() * float32(simState.width),
			y:  rand.Float32() * float32(simState.height),
			dx: 0,
			dy: 0,
		})
	}

	// setup ticker
	ticker := time.NewTicker(time.Second / 60)
	defer ticker.Stop()
	lastTime := time.Now()

	// wait group
	jobs := make(chan SimJob, numThreads)
	var wg sync.WaitGroup
	for i := 0; i < numThreads; i++ {
		go worker(jobs, &wg)
	}

	go broadcastFrames(framesChannel, &framePool)

	for range ticker.C {
		frameCount++
		now := time.Now()
		simState.dt = float32(now.Sub(lastTime).Seconds())
		lastTime = now

		if frameCount%60 == 0 {
			log.Println(simState.dt)
		}

		wg.Add(numThreads)
		// we are ok if this is out of sync a little as it means
		// it only checks more data than it needs to. We could check ALL inputs
		// but we want to maybe support 10k inputs so better to only check what is connected.
		numClients := len(clients)

		for i := 0; i < numThreads; i++ {
			startIndex := i * particlesPerThread
			endIndex := startIndex + particlesPerThread
			if i == numThreads-1 {
				endIndex = particleCount
			}
			jobs <- SimJob{startIndex, endIndex, simState, inputs, numClients}
		}

		// wait for them to complete
		wg.Wait()

		if frameCount%2 == 0 {
			continue
		}

		frame := framePool.Get().(*Frame)
		framebuffer := frame.data
		for i := range framebuffer {
			framebuffer[i] = 0
		}

		for _, p := range particles {
			x := int(p.x)
			y := int(p.y)
			if x >= 0 && x < int(simState.width) && y >= 0 && y < int(simState.height) {
				idx := (y*int(simState.width) + x)
				if idx < int(simState.width*simState.height) {
					byteIndex := idx / 8
					bitOffset := idx % 8
					if byteIndex < len(framebuffer) {
						framebuffer[byteIndex] |= (1 << bitOffset)
					}
				}
			}
		}

		go func(data []byte) {
			// var compressedBuffer bytes.Buffer
			// zlibWriter := zlib.NewWriter(&compressedBuffer)
			// if _, err := zlibWriter.Write(data); err != nil {
			// 	log.Println("zlib write failed:", err)
			// 	return
			// }
			// zlibWriter.Close()
			// frame.Delta = data
			select {
			case framesChannel <- frame:
			default:
				log.Print("too slow")
				framePool.Put(frame)
			}
		}(frame.data)
	}
}

func wsHandler(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println("Upgrade failed:", err)
		return
	}

	clientsMu.Lock()
	if len(clients) >= maxClients {
		clientsMu.Unlock()
		log.Println("Max clients reached")
		conn.Close()
		return
	}
	clients = append(clients, conn)
	clientSendChannelMap[conn] = make(chan []byte)

	clientsMu.Unlock()

	log.Printf("Client connected\n")

	go writePump(conn)

	defer func() {
		clientsMu.Lock()
		for i, c := range clients {
			if c == conn {
				clients = append(clients[:i], clients[i+1:]...)
				inputs[i] = Input{}
				cameras[i] = ClientCam{}
				cameras[i].Width = 1
				cameras[i].Height = 1
				break
			}
		}
		clientsMu.Unlock()
		close(clientSendChannelMap[conn])
		delete(clientSendChannelMap, conn)
		conn.Close()
		log.Printf("Client disconnected\n")
	}()

	for {
		mt, message, err := conn.ReadMessage()
		if err != nil {
			log.Println("Read failed:", err)
			return
		}
		if mt == websocket.BinaryMessage {
			reader := bytes.NewReader(message)
			var input Input
			// interpret x and y as offsets.
			var cam ClientCam
			// read input
			errInput := binary.Read(reader, binary.LittleEndian, &input)
			if errInput != nil {
				log.Println("Binary read failed input:", errInput)
				continue
			}
			// read cam input
			errCam := binary.Read(reader, binary.LittleEndian, &cam)
			if errCam != nil {
				log.Println("Binary read failed cam:", errCam)
				continue
			}

			// maybe later we lock or figure out way to not need as lock in a hot path
			// this is fine as only chance of bad data is if someone connect or drops whilst updating input
			idx := findClientIndex(conn)
			if idx != -1 && idx < maxClients {
				cameras[idx].Width = cam.Width
				cameras[idx].Height = cam.Height
				cameras[idx].X += cam.X
				cameras[idx].Y += cam.Y
				inputs[idx] = input
				inputs[idx].X += cameras[idx].X
				inputs[idx].Y += cameras[idx].Y
			}
		}
	}
}

const (
	OpCodeFrame   byte = 0x01
	OpCodeSimSize byte = 0x02
)

func broadcastFrames(ch <-chan *Frame, pool *sync.Pool) {
	for {
		frame := <-ch
		frameBuffer := frame.data

		clientsMu.Lock()
		for i, conn := range clients {
			cam := &cameras[i]
			x, y, width, height := int32(cam.X), int32(cam.Y), cam.Width, cam.Height

			maxCamX := int32(simState.width) - width
			if x < 0 {
				x = 0
				cam.X = 0
			} else if x > maxCamX {
				x = maxCamX
				cam.X = float32(maxCamX)
			}

			maxCamY := int32(simState.height) - height
			if y < 0 {
				y = 0
				cam.Y = 0
			} else if y > maxCamY {
				y = maxCamY
				cam.Y = float32(maxCamY)
			}

			if x+width > int32(simState.width) {
				width = int32(simState.width) - x
			}
			if y+height > int32(simState.height) {
				height = int32(simState.height) - y
			}
			dataToSend := make([]byte, (width*height+7)/8)

			for row := int32(0); row < height; row++ {
				for col := int32(0); col < width; col++ {
					mainFrameIndex := ((y+row)*int32(simState.width) + (x + col))
					dataToSendIndex := (row*width + col)

					if mainFrameIndex/8 < int32(len(frameBuffer)) && dataToSendIndex/8 < int32(len(dataToSend)) {
						isSet := (frameBuffer[mainFrameIndex/8] >> (mainFrameIndex % 8)) & 1
						if isSet == 1 {
							dataToSend[dataToSendIndex/8] |= (1 << (dataToSendIndex % 8))
						}
					}
				}
			}

			select {
			case clientSendChannelMap[conn] <- dataToSend:
			default:
				log.Printf("Client %d's channel is full, dropping frame.", i)
			}
		}
		clientsMu.Unlock()
		pool.Put(frame)
	}
}

func writePump(conn *websocket.Conn) {
	var channel = clientSendChannelMap[conn]
	for {
		message, ok := <-channel
		if !ok {
			return
		}

		if err := conn.WriteMessage(websocket.BinaryMessage, message); err != nil {
			log.Printf("Write to client failed: %v", err)
			return
		}
	}
}

func findClientIndex(conn *websocket.Conn) int {
	for i, c := range clients {
		if c == conn {
			return i
		}
	}
	return -1
}

func worker(jobs <-chan SimJob, wg *sync.WaitGroup) {
	for job := range jobs {
		for p := job.startIndex; p < job.endIndex; p++ {
			for i := 0; i < job.numClients; i++ {
				input := job.inputs[i]
				if input.IsTouchDown {
					dirx := input.X - particles[p].x
					diry := input.Y - particles[p].y
					dist := dirx*dirx + diry*diry
					if dist < 100000 && dist > 1 {
						var grav = 4 / float32(math.Sqrt(float64(dist)))
						particles[p].dx += dirx * job.simState.dt * grav * 3
						particles[p].dy += diry * job.simState.dt * grav * 3
					}
				}
			}

			particles[p].x += particles[p].dx
			particles[p].y += particles[p].dy
			particles[p].dx *= friction
			particles[p].dy *= friction

			if particles[p].x < 0 || particles[p].x >= float32(job.simState.width) {
				particles[p].x -= particles[p].dx
				particles[p].dx *= -1
			}
			if particles[p].y < 0 || particles[p].y >= float32(job.simState.height) {
				particles[p].y -= particles[p].dy
				particles[p].dy *= -1
			}
		}
		wg.Done()
	}
}

func main() {
	go startSim()
	http.HandleFunc("/ws", wsHandler)
	http.Handle("/", http.FileServer(http.Dir("./public")))

	// go func() {
	// 	log.Println(http.ListenAndServe("localhost:6061", nil))
	// }()

	var port = 41069
	log.Println(fmt.Sprintf("Server started on :%d", port))
	if err := http.ListenAndServe(fmt.Sprintf(":%d", port), nil); err != nil {
		log.Fatal("ListenAndServe:", err)
	}

}
