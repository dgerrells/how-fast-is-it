package main

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
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

type Frame struct {
	FullBuffer []byte
	Delta      []byte
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

var clientFullFrameRequestFlags [maxClients]bool

// var clientSendChannels [maxClients]chan []byte
var clientSendChannelMap = make(map[*websocket.Conn]chan []byte)

const numBuffers = 3
const maxClients = 100
const particleCount = 1000000

var numThreads = runtime.NumCPU() - 1
var particlesPerThread = particleCount / numThreads

const friction = 0.99

var (
	clients    []*websocket.Conn
	clientsMu  sync.Mutex
	frameCount uint64
	inputs     [maxClients]Input
	particles  = []Particle{}
)

func startSim() {
	simState := SimState{
		dt:     1.0 / 60.0,
		width:  720,
		height: 480,
	}

	framePool := sync.Pool{
		New: func() interface{} {
			return &Frame{
				FullBuffer: make([]byte, simState.width*simState.height),
				Delta:      make([]byte, simState.width*simState.height),
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

	lastFrameBuffer := make([]byte, simState.width*simState.height)

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
		framebuffer := frame.FullBuffer
		for i := range framebuffer {
			framebuffer[i] = 0
		}

		for _, p := range particles {
			x := int(p.x)
			y := int(p.y)
			if x >= 0 && x < int(simState.width) && y >= 0 && y < int(simState.height) {
				idx := (y*int(simState.width) + x)
				if idx < len(framebuffer) {
					sum := int16(framebuffer[idx]) + 1
					if sum > math.MaxUint8 {
						sum = 255
					}
					framebuffer[idx] = byte(sum)
				}
			}
		}

		deltaBytes := CreateDeltaBuffer(lastFrameBuffer, framebuffer)
		rleBytes := RLEncode(deltaBytes)
		copy(lastFrameBuffer, framebuffer)

		go func(data []byte) {
			var compressedBuffer bytes.Buffer
			zlibWriter := zlib.NewWriter(&compressedBuffer)
			if _, err := zlibWriter.Write(data); err != nil {
				log.Println("zlib write failed:", err)
				return
			}
			zlibWriter.Close()
			frame.Delta = compressedBuffer.Bytes()
			// frame.Delta = data
			select {
			case framesChannel <- frame:
			default:
				log.Print("too slow")
				framePool.Put(frame)
			}
		}(rleBytes)
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
	idx := len(clients) - 1
	clientFullFrameRequestFlags[idx] = true
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
			var input Input
			err := binary.Read(bytes.NewReader(message), binary.LittleEndian, &input)
			if err != nil {
				log.Println("Binary read failed:", err)
				continue
			}

			// maybe later we lock or figure out way to not need a lock in a hot path
			// this is fine as only chance of bad data is if someone connect or drops whilst updating input
			idx := findClientIndex(conn)
			if idx != -1 && idx < maxClients {
				inputs[idx] = input
			}
		}
	}
}

func main() {
	go startSim()
	http.HandleFunc("/ws", wsHandler)
	http.Handle("/", http.FileServer(http.Dir("./public")))

	// go func() {
	// 	log.Println(http.ListenAndServe("localhost:6061", nil))
	// }()

	log.Println("Server started on :42069")
	if err := http.ListenAndServe(":42069", nil); err != nil {
		log.Fatal("ListenAndServe:", err)
	}

}

const (
	OpCodeFullFrame  byte = 0x01
	OpCodeDeltaFrame byte = 0x02
)

var fullFrameBuffer bytes.Buffer
var deltaFrameBuffer bytes.Buffer

func broadcastFrames(ch <-chan *Frame, pool *sync.Pool) {
	for {
		frame := <-ch
		fullFrameBuffer.Reset()
		fullFrameBuffer.WriteByte(OpCodeFullFrame)
		fullFrameBuffer.Write(frame.FullBuffer)
		fullFrameBytes := fullFrameBuffer.Bytes()

		deltaFrameBuffer.Reset()
		deltaFrameBuffer.WriteByte(OpCodeDeltaFrame)
		deltaFrameBuffer.Write(frame.Delta)
		deltaFrameBytes := deltaFrameBuffer.Bytes()

		clientsMu.Lock()
		for i, conn := range clients {

			var dataToSend []byte
			if clientFullFrameRequestFlags[i] {
				dataToSend = fullFrameBytes
				clientFullFrameRequestFlags[i] = false
			} else {
				dataToSend = deltaFrameBytes
			}

			select {
			case clientSendChannelMap[conn] <- dataToSend:
			default:
				log.Printf("Client %d's channel is full, dropping frame. Requesting full frame.", i)
				clientFullFrameRequestFlags[i] = true
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
					if dist < 10000 && dist > 1 {
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

func CreateDeltaBuffer(oldBuffer, newBuffer []byte) []byte {
	if len(oldBuffer) != len(newBuffer) {
		return newBuffer
	}
	deltaBuffer := make([]byte, len(newBuffer))

	for i := 0; i < len(newBuffer); i++ {
		diff := int16(newBuffer[i]) - int16(oldBuffer[i])
		deltaBuffer[i] = zigZagEncode(int8(diff))
	}

	return deltaBuffer
}

func zigZagEncode(n int8) byte {
	if n >= 0 {
		return byte(n * 2)
	}
	return byte(-n*2 - 1)
}

func RLEncode(data []byte) []byte {
	var encoded []byte
	if len(data) == 0 {
		return encoded
	}

	const sentinel byte = 255

	i := 0
	for i < len(data) {
		currentByte := data[i]
		count := 1
		j := i + 1
		for j < len(data) && data[j] == currentByte && count < 254 {
			count++
			j++
		}

		if count > 1 || currentByte == sentinel {
			encoded = append(encoded, sentinel)
			encoded = append(encoded, byte(count))
			encoded = append(encoded, currentByte)
		} else {
			encoded = append(encoded, currentByte)
		}
		i = j
	}

	return encoded
}
