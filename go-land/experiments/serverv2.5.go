// package main

// import (
// 	"bytes"
// 	"encoding/binary"
// 	"log"
// 	"math"
// 	"math/rand"
// 	"net/http"
// 	"sync"
// 	"sync/atomic"
// 	"time"

// 	"github.com/gorilla/websocket"
// )

// type Particle struct {
// 	x  float32
// 	y  float32
// 	dx float32
// 	dy float32
// }

// type SimState struct {
// 	dt     float32
// 	width  uint32
// 	height uint32
// }

// type Input struct {
// 	X           float32
// 	Y           float32
// 	IsTouchDown bool
// }

// var upgrader = websocket.Upgrader{
// 	ReadBufferSize:    1024,
// 	WriteBufferSize:   1024,
// 	EnableCompression: true,
// 	CheckOrigin: func(r *http.Request) bool {
// 		return true
// 	},
// }

// const numBuffers = 3

// var (
// 	framebuffers          [][]byte
// 	nextWriteBufferIndex  atomic.Int32
// 	activeReadBufferIndex atomic.Int32
// )

// const maxClients = 10
// const particleCount = 2000000
// const numThreads = 4

// var (
// 	clients    []*websocket.Conn
// 	clientsMu  sync.Mutex
// 	frameCount uint64
// 	inputs     [maxClients]Input
// 	particles  = []Particle{}
// )

// var compressedFrameChannel = make(chan []byte, 3)

// func startSim() {
// 	simState := SimState{
// 		dt:     1.0 / 60.0,
// 		width:  720,
// 		height: 480,
// 	}

// 	framebuffers = make([][]byte, numBuffers)
// 	for i := 0; i < numBuffers; i++ {
// 		framebuffers[i] = make([]byte, simState.width*simState.height)
// 	}

// 	nextWriteBufferIndex.Store(0)
// 	activeReadBufferIndex.Store(numBuffers - 1)

// 	// gen particles
// 	for i := 0; i < particleCount; i++ {
// 		particles = append(particles, Particle{
// 			x:  rand.Float32() * float32(simState.width),
// 			y:  rand.Float32() * float32(simState.height),
// 			dx: rand.Float32()*10 - 5,
// 			dy: rand.Float32()*10 - 5,
// 		})
// 	}

// 	// setup ticker
// 	ticker := time.NewTicker(time.Second / 60)
// 	defer ticker.Stop()
// 	lastTime := time.Now()

// 	// wait group
// 	var wg sync.WaitGroup
// 	const particlesPerThread = particleCount / numThreads

// 	for range ticker.C {
// 		frameCount++
// 		now := time.Now()
// 		simState.dt = float32(now.Sub(lastTime).Seconds())
// 		lastTime = now

// 		if frameCount%60 == 0 {
// 			log.Println(simState.dt)
// 		}

// 		wg.Add(numThreads)
// 		// we are ok if this is out of sync a little as it means
// 		// it only checks more data than it needs to. We could check ALL inputs
// 		// but we want to maybe support 10k inputs so better to only check what is connected.
// 		numClients := len(clients)
// 		const friction = 0.99

// 		for i := 0; i < numThreads; i++ {
// 			go func(threadID int) {
// 				defer wg.Done()

// 				startIndex := threadID * particlesPerThread
// 				endIndex := startIndex + particlesPerThread

// 				if threadID == numThreads-1 {
// 					endIndex = particleCount
// 				}

// 				for p := startIndex; p < endIndex; p++ {
// 					for i := 0; i < numClients; i++ {
// 						input := inputs[i]
// 						if input.IsTouchDown {
// 							dirx := input.X - particles[p].x
// 							diry := input.Y - particles[p].y
// 							dist := dirx*dirx + diry*diry
// 							if dist < 10000 && dist > 1 {
// 								var grav = 4 / float32(math.Sqrt(float64(dist)))
// 								particles[p].dx += dirx * simState.dt * grav * 3
// 								particles[p].dy += diry * simState.dt * grav * 3
// 							}
// 						}
// 					}

// 					particles[p].x += particles[p].dx
// 					particles[p].y += particles[p].dy
// 					particles[p].dx *= friction
// 					particles[p].dy *= friction

// 					if particles[p].x < 0 || particles[p].x >= float32(simState.width) {
// 						particles[p].x -= particles[p].dx
// 						particles[p].dx *= -1
// 					}
// 					if particles[p].y < 0 || particles[p].y >= float32(simState.height) {
// 						particles[p].y -= particles[p].dy
// 						particles[p].dy *= -1
// 					}
// 				}
// 			}(i)
// 		}

// 		// wait for them to complete
// 		wg.Wait()

// 		framebuffer := getWriteBuffer()
// 		copy(framebuffer, bytes.Repeat([]byte{0}, len(framebuffer)))

// 		for _, p := range particles {
// 			x := int(p.x)
// 			y := int(p.y)
// 			if x >= 0 && x < int(simState.width) && y >= 0 && y < int(simState.height) {
// 				idx := (y*int(simState.width) + x)
// 				if idx < len(framebuffer) {
// 					sum := int16(framebuffer[idx]) + 1
// 					if sum > math.MaxUint8 {
// 						sum = 255
// 					}
// 					framebuffer[idx] = byte(sum)
// 				}
// 			}
// 		}
// 		swapBuffers()

// 		go func(data []byte) {
// 			// var compressedBuffer bytes.Buffer
// 			// zlibWriter := zlib.NewWriter(&compressedBuffer)
// 			// if _, err := zlibWriter.Write(data); err != nil {
// 			// 	log.Println("zlib write failed:", err)
// 			// 	return
// 			// }
// 			// zlibWriter.Close()

// 			// Non-blocking send: if the channel is full, the oldest frame is dropped
// 			select {
// 			case compressedFrameChannel <- framebuffer:
// 			default:
// 				// Channel is full, drop the frame
// 			}
// 		}(framebuffer)
// 	}
// }

// func wsHandler(w http.ResponseWriter, r *http.Request) {
// 	conn, err := upgrader.Upgrade(w, r, nil)
// 	if err != nil {
// 		log.Println("Upgrade failed:", err)
// 		return
// 	}

// 	clientsMu.Lock()
// 	if len(clients) >= maxClients {
// 		clientsMu.Unlock()
// 		log.Println("Max clients reached")
// 		conn.Close()
// 		return
// 	}
// 	clients = append(clients, conn)
// 	clientID := len(clients) - 1
// 	clientsMu.Unlock()

// 	log.Printf("Client %d connected\n", clientID)

// 	defer func() {
// 		clientsMu.Lock()
// 		for i, c := range clients {
// 			if c == conn {
// 				clients = append(clients[:i], clients[i+1:]...)
// 				inputs[i] = Input{}
// 				break
// 			}
// 		}
// 		clientsMu.Unlock()
// 		conn.Close()
// 		log.Printf("Client %d disconnected\n", clientID)
// 	}()

// 	go func() {
// 		defer conn.Close()

// 		for {
// 			mt, message, err := conn.ReadMessage()
// 			if err != nil {
// 				log.Println("Read failed:", err)
// 				return
// 			}
// 			if mt == websocket.BinaryMessage {
// 				var input Input
// 				err := binary.Read(bytes.NewReader(message), binary.LittleEndian, &input)
// 				if err != nil {
// 					log.Println("Binary read failed:", err)
// 					continue
// 				}

// 				// maybe later we lock or figure out way to not need a lock in a hot path
// 				// this is fine as only chance of bad data is if someone connect or drops whilst updating input
// 				idx := findClientIndex(conn)
// 				if idx != -1 && idx < maxClients {
// 					inputs[idx] = input
// 				}
// 			}
// 		}
// 	}()

// 	ticker := time.NewTicker(time.Second / 30)
// 	defer ticker.Stop()

// 	// var compressedBuffer bytes.Buffer
// 	// zlibWriter := zlib.NewWriter(&compressedBuffer)

// 	for range ticker.C {
// 		data := getReadBuffer()
// 		// compressedBuffer.Reset()
// 		// zlibWriter.Reset(&compressedBuffer)

// 		// if _, err := zlibWriter.Write(data); err != nil {
// 		// 	log.Println("zlib write failed:", err)
// 		// 	continue
// 		// }
// 		// if err := zlibWriter.Close(); err != nil {
// 		// 	log.Println("zlib close failed:", err)
// 		// 	continue
// 		// }

// 		// compressedData := compressedBuffer.Bytes()

// 		if err := conn.WriteMessage(websocket.BinaryMessage, data); err != nil {
// 			log.Println("Write failed:", err)
// 			break
// 		}
// 	}
// }

// // func main() {
// // 	go startSim()

// // 	http.HandleFunc("/ws", wsHandler)
// // 	log.Println("Server started on :8080")
// // 	if err := http.ListenAndServe(":8080", nil); err != nil {
// // 		log.Fatal("ListenAndServe:", err)
// // 	}
// // }

// func main() {
// 	go startSim()

// 	http.HandleFunc("/ws", wsHandler)

// 	http.Handle("/", http.FileServer(http.Dir("./public")))

// 	log.Println("Server started on :8080")
// 	if err := http.ListenAndServe(":8080", nil); err != nil {
// 		log.Fatal("ListenAndServe:", err)
// 	}
// }

// func findClientIndex(conn *websocket.Conn) int {
// 	for i, c := range clients {
// 		if c == conn {
// 			return i
// 		}
// 	}
// 	return -1
// }

// func swapBuffers() {
// 	currentWriteIndex := nextWriteBufferIndex.Load()
// 	nextWriteBufferIndex.Store((currentWriteIndex + 1) % numBuffers)

// 	activeReadBufferIndex.Store(currentWriteIndex)
// }

// func getReadBuffer() []byte {
// 	index := activeReadBufferIndex.Load()
// 	return framebuffers[index]
// }

// func getWriteBuffer() []byte {
// 	index := nextWriteBufferIndex.Load()
// 	return framebuffers[index]
// }
