package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"log"
	"math"
	"math/rand"
	"net/http"
	"unsafe"

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
	inputs     *[maxClients]Input
	numClients int
	frameData  *[]byte
}

const (
	OpCodeFrame   byte = 0x01
	OpCodeSimData byte = 0x02
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

// var clientSendChannels [maxClients]chan []byte
var clientSendChannelMap = make(map[*websocket.Conn]chan []byte)

const maxClients = 1000
const particleCount = 4_000_000

var numThreads = int(math.Min(math.Max(float64(runtime.NumCPU()-1), 1), 8))
var particlesPerThread = particleCount / numThreads

const friction = 0.988

var (
	clients    []*websocket.Conn
	clientsMu  sync.Mutex
	frameCount uint64
	inputs     [maxClients]Input
	cameras    [maxClients]ClientCam
	particles  = []Particle{}
	simState   = SimState{
		dt:     1.0 / 60.0,
		width:  2200,
		height: 2200,
	}
)

func startSim() {
	var resetInterval uint64 = (60 * 60 * 5)

	framePool := sync.Pool{
		New: func() interface{} {
			return &Frame{
				data: make([]byte, simState.width*simState.height),
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

		if frameCount%30 == 0 {
			log.Println(simState.dt)
			log.Println(fmt.Sprintf("FPS: %f", 1/simState.dt))
		}

		if frameCount%resetInterval == 0 {
			for i := 0; i < particleCount; i++ {
				var p = &particles[i]
				p.x = rand.Float32() * float32(simState.width)
				p.y = rand.Float32() * float32(simState.height)
			}
		}

		wg.Add(numThreads)
		// we are ok if this is out of sync a little as it means
		// it only checks more data than it needs to. We could check ALL inputs
		// but we want to maybe support 10k inputs so better to only check what is connected.
		numClients := len(clients)

		// frame
		frame := framePool.Get().(*Frame)
		framebuffer := frame.data
		for i := range framebuffer {
			framebuffer[i] = 0
		}

		for i := 0; i < numThreads; i++ {
			startIndex := i * particlesPerThread
			endIndex := startIndex + particlesPerThread
			if i == numThreads-1 {
				endIndex = particleCount
			}
			jobs <- SimJob{startIndex, endIndex, simState, &inputs, numClients, &framebuffer}
		}

		// wait for them to complete
		wg.Wait()

		if frameCount%2 == 0 {
			select {
			case framesChannel <- frame:
			default:
				log.Print("too slow")
				framePool.Put(frame)
			}
		}
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
	clientSendChannelMap[conn] = make(chan []byte, 2)

	clientsMu.Unlock()

	log.Printf("Client connected\n")

	go writePump(conn)

	defer func() {
		clientsMu.Lock()
		for i, c := range clients {
			if c == conn {
				clients = append(clients[:i], clients[i+1:]...)
				for j := i; j < len(clients); j++ {
					inputs[j] = inputs[j+1]
					cameras[j] = cameras[j+1]
				}
				var endIndex = len(clients)
				inputs[endIndex] = Input{}
				cameras[endIndex] = ClientCam{}
				cameras[endIndex].X = float32(simState.width)/2 - 300
				cameras[endIndex].Y = float32(simState.height)/2 - 300
				cameras[endIndex].Width = 1
				cameras[endIndex].Height = 1
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
			var touchInput Input
			// interpret x and y as offsets.
			var camInput ClientCam
			// read input
			errInput := binary.Read(reader, binary.LittleEndian, &touchInput)
			if errInput != nil {
				log.Println("Binary read failed input:", errInput)
				continue
			}
			// read cam input
			errCam := binary.Read(reader, binary.LittleEndian, &camInput)
			if errCam != nil {
				log.Println("Binary read failed cam:", errCam)
				continue
			}

			// maybe later we lock or figure out way to not need as lock in a hot path
			// this is fine as only chance of bad data is if someone connect or drops whilst updating input
			idx := findClientIndex(conn)
			if idx != -1 && idx < maxClients {

				cameras[idx].Width = camInput.Width
				cameras[idx].Height = camInput.Height
				cameras[idx].X += camInput.X
				cameras[idx].Y += camInput.Y
				inputs[idx] = touchInput
				inputs[idx].X += cameras[idx].X
				inputs[idx].Y += cameras[idx].Y

				// clamp to valid camera range
				var cam = &cameras[idx]
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
					cam.Width = width
				}
				if y+height > int32(simState.height) {
					height = int32(simState.height) - y
					cam.Height = height
				}
			}
		}
	}
}

var uint64ToByteLUT = make(map[uint64]byte)

func init() {
	var byteSlice = make([]byte, 8)
	for i := 0; i < 256; i++ {
		for bit := 0; bit < 8; bit++ {
			if (i>>bit)&1 == 1 {
				byteSlice[bit] = 1
			} else {
				byteSlice[bit] = 0
			}
		}
		uint64ToByteLUT[BytesToUint64Unsafe(byteSlice)] = byte(i)
	}
}

func BytesToUint64Unsafe(b []byte) uint64 {
	return *(*uint64)(unsafe.Pointer(&b[0]))
}

func broadcastFrames(ch <-chan *Frame, pool *sync.Pool) {
	for {
		frame := <-ch
		frameBuffer := frame.data

		clientsMu.Lock()
		for i, conn := range clients {
			cam := &cameras[i]
			var x, y, width, height = int32(cam.X), int32(cam.Y), cam.Width, cam.Height
			var isValidCamSize = width < int32(simState.width) && height < int32(simState.height) && width > 0 && height > 0

			if isValidCamSize {
				dataToSendSize := (width*height+7)/8 + 1
				dataToSend := make([]byte, dataToSendSize)
				dataToSend[0] = OpCodeFrame
				outputByteIndex := int32(1)

				for row := int32(0); row < height; row++ {
					yOffset := (y + row) * int32(simState.width)
					for col := int32(0); col < width; col += 8 {
						fullBufferIndex := yOffset + (x + col)
						chunk := frameBuffer[fullBufferIndex : fullBufferIndex+8]
						key := BytesToUint64Unsafe(chunk)

						packedByte, _ := uint64ToByteLUT[key]

						if outputByteIndex < int32(len(dataToSend)) {
							dataToSend[outputByteIndex] = packedByte
							outputByteIndex++
						}
					}
				}

				select {
				case clientSendChannelMap[conn] <- dataToSend:
				default:
					log.Printf("Client %d's channel is full, dropping frame.", i)
				}
			}

			camMessage := new(bytes.Buffer)
			camMessage.WriteByte(OpCodeSimData)
			binary.Write(camMessage, binary.LittleEndian, x)
			binary.Write(camMessage, binary.LittleEndian, y)
			binary.Write(camMessage, binary.LittleEndian, simState.width)
			binary.Write(camMessage, binary.LittleEndian, simState.height)
			select {
			case clientSendChannelMap[conn] <- camMessage.Bytes():
			default:
				log.Printf("Client %d's camera channel is full, dropping frame.", i)
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
		var validInputs []Input
		var frame = *job.frameData
		var frictionFactor = float32(math.Pow(float64(friction), float64(job.simState.dt*60)))
		for _, input := range job.inputs {
			if input.IsTouchDown {
				validInputs = append(validInputs, input)
			}
		}
		var fSimWidth = float32(job.simState.width)
		var fSimHeight = float32(job.simState.height)
		var gravPower = job.simState.dt * 5
		var pullDist float32 = 32300

		for i := job.startIndex; i < job.endIndex; i++ {
			p := &particles[i]

			for _, input := range validInputs {
				dirx := input.X - p.x
				diry := input.Y - p.y
				dist := dirx*dirx + diry*diry
				if dist < pullDist && dist > 1 {
					var grav = 4 / float32(math.Sqrt(float64(dist)))
					p.dx += dirx * gravPower * grav
					p.dy += diry * gravPower * grav
				}
			}

			p.x += p.dx
			p.y += p.dy
			p.dx *= frictionFactor
			p.dy *= frictionFactor

			if p.x < 0 || p.x >= fSimWidth {
				p.x -= p.dx
				p.dx *= -1
			}
			if p.y < 0 || p.y >= fSimHeight {
				p.y -= p.dy
				p.dy *= -1
			}

			if frameCount%2 == 0 {
				if p.x >= 0 && p.x < fSimWidth && p.y >= 0 && p.y < fSimHeight {
					x := uint32(p.x)
					y := uint32(p.y)
					idx := (y*simState.width + x)
					frame[idx] = 1
				}
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
