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
// 	ReadBufferSize:  1024,
// 	WriteBufferSize: 1024,
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

// const maxClients = 10
// const particleCount = 5000000
// const numThreads = 8

// var particles = []Particle{}
// var inputs [maxClients]Input
// var nextClientID uint32
// var frameCount uint64

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
// 		numClients := atomic.LoadUint32(&nextClientID)
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
// 					for i := uint32(0); i < numClients; i++ {
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
// 					framebuffer[idx] += 1
// 				}
// 			}
// 		}
// 		swapBuffers()
// 	}
// }

// func wsHandler(w http.ResponseWriter, r *http.Request) {
// 	conn, err := upgrader.Upgrade(w, r, nil)
// 	if err != nil {
// 		log.Println("Upgrade failed:", err)
// 		return
// 	}

// 	clientID := atomic.AddUint32(&nextClientID, 1) - 1
// 	if clientID >= maxClients {
// 		log.Println("Max clients reached")
// 		conn.Close()
// 		return
// 	}
// 	log.Printf("Client %d connected\n", clientID)

// 	defer conn.Close()

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
// 				inputs[clientID] = input
// 			}
// 		}
// 	}()

// 	ticker := time.NewTicker(time.Second / 30)
// 	defer ticker.Stop()

// 	for range ticker.C {
// 		data := getReadBuffer()
// 		if err := conn.WriteMessage(websocket.BinaryMessage, data); err != nil {
// 			log.Println("Write failed:", err)
// 			break
// 		}
// 	}
// }

// func main() {
// 	go startSim()

// 	http.HandleFunc("/ws", wsHandler)
// 	log.Println("Server started on :8080")
// 	if err := http.ListenAndServe(":8080", nil); err != nil {
// 		log.Fatal("ListenAndServe:", err)
// 	}
// }
