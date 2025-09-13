package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"math/rand"
	"net/http"
	"runtime"
	"sync"
	"time"
	"unsafe"

	"github.com/pion/webrtc/v3"
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

const maxClients = 1000
const particleCount = 2_500_000

var numThreads = int(math.Min(math.Max(float64(runtime.NumCPU()-1), 1), 8))
var particlesPerThread = particleCount / numThreads

const friction = 0.988

var (
	api               *webrtc.API
	peerConnections   []*webrtc.PeerConnection
	peerConnectionsMu sync.Mutex
	// Updated map for the output data channel
	outputDataChannels = make(map[*webrtc.PeerConnection]*webrtc.DataChannel)
	inputs             [maxClients]Input
	cameras            [maxClients]ClientCam
	particles          = []Particle{}
	simState           = SimState{
		dt:     1.0 / 60.0,
		width:  2800,
		height: 2800,
	}
	frameCount uint64
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

	for i := 0; i < particleCount; i++ {
		particles = append(particles, Particle{
			x:  rand.Float32() * float32(simState.width),
			y:  rand.Float32() * float32(simState.height),
			dx: 0,
			dy: 0,
		})
	}

	ticker := time.NewTicker(time.Second / 60)
	defer ticker.Stop()
	lastTime := time.Now()

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
		peerConnectionsMu.Lock()
		numClients := len(peerConnections)
		peerConnectionsMu.Unlock()

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

type OfferRequest struct {
	SDP webrtc.SessionDescription `json:"sdp"`
}

type AnswerResponse struct {
	SDP *webrtc.SessionDescription `json:"sdp"`
}

func webrtcHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var offerReq OfferRequest
	if err := json.NewDecoder(r.Body).Decode(&offerReq); err != nil {
		http.Error(w, "Invalid offer payload", http.StatusBadRequest)
		return
	}

	peerConnection, err := webrtc.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		log.Printf("Error creating peer connection: %v", err)
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	// --- NEW: Create a data channel for sending frames to the client ---
	frameDataChannel, err := peerConnection.CreateDataChannel("frame-data", nil)
	if err != nil {
		log.Printf("Error creating data channel: %v", err)
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	frameDataChannel.OnOpen(func() {
		log.Printf("Frame Data Channel opened for peer connection %p", peerConnection)
		peerConnectionsMu.Lock()
		outputDataChannels[peerConnection] = frameDataChannel
		peerConnectionsMu.Unlock()
	})

	frameDataChannel.OnClose(func() {
		log.Printf("Frame Data Channel closed for peer connection %p", peerConnection)
		peerConnectionsMu.Lock()
		delete(outputDataChannels, peerConnection)
		peerConnectionsMu.Unlock()
	})
	// --- END NEW ---

	// Handle incoming data channels (e.g., input from client)
	peerConnection.OnDataChannel(func(d *webrtc.DataChannel) {
		log.Printf("New DataChannel %s", d.Label())
		if d.Label() == "input-data" {
			d.OnMessage(func(msg webrtc.DataChannelMessage) {
				reader := bytes.NewReader(msg.Data)
				var touchInput Input
				var camInput ClientCam

				errInput := binary.Read(reader, binary.LittleEndian, &touchInput)
				if errInput != nil {
					log.Println("Binary read failed input:", errInput)
					return
				}
				errCam := binary.Read(reader, binary.LittleEndian, &camInput)
				if errCam != nil {
					log.Println("Binary read failed cam:", errCam)
					return
				}

				idx := findPeerConnectionIndex(peerConnection)
				if idx != -1 && idx < maxClients {
					cameras[idx].Width = camInput.Width
					cameras[idx].Height = camInput.Height
					cameras[idx].X += camInput.X
					cameras[idx].Y += camInput.Y
					inputs[idx] = touchInput
					inputs[idx].X += cameras[idx].X
					inputs[idx].Y += cameras[idx].Y

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
			})

			d.OnClose(func() {
				log.Printf("Input DataChannel closed for peer connection %p", peerConnection)
				peerConnectionsMu.Lock()
				idx := findPeerConnectionIndex(peerConnection)
				if idx != -1 {
					peerConnections = append(peerConnections[:idx], peerConnections[idx+1:]...)
					inputs[idx] = Input{}
					cameras[idx] = ClientCam{}
					cameras[idx].X = float32(simState.width)/2 - 300
					cameras[idx].Y = float32(simState.height)/2 - 300
					cameras[idx].Width = 1
					cameras[idx].Height = 1
				}
				peerConnectionsMu.Unlock()
				peerConnection.Close()
			})
		}
	})

	if err := peerConnection.SetRemoteDescription(offerReq.SDP); err != nil {
		log.Printf("Error setting remote description: %v", err)
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	answer, err := peerConnection.CreateAnswer(nil)
	if err != nil {
		log.Printf("Error creating answer: %v", err)
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	gatherComplete := webrtc.GatheringCompletePromise(peerConnection)
	if err := peerConnection.SetLocalDescription(answer); err != nil {
		log.Printf("Error setting local description: %v", err)
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	<-gatherComplete
	if peerConnection.LocalDescription() == nil {
		http.Error(w, "Local description is nil", http.StatusInternalServerError)
		return
	}

	peerConnectionsMu.Lock()
	peerConnections = append(peerConnections, peerConnection)
	peerConnectionsMu.Unlock()

	response := AnswerResponse{SDP: peerConnection.LocalDescription()}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(response); err != nil {
		log.Printf("Error encoding answer: %v", err)
	}
	log.Printf("WebRTC client connected")
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
		currentFrameID := uint32(frameCount)

		peerConnectionsMu.Lock()
		for pc, dc := range outputDataChannels {
			if dc.ReadyState() != webrtc.DataChannelStateOpen {
				continue
			}
			idx := findPeerConnectionIndex(pc)
			if idx == -1 {
				continue
			}
			cam := &cameras[idx]

			x, y, width, height := int32(cam.X), int32(cam.Y), cam.Width, cam.Height
			isValidCamSize := width < int32(simState.width) && height < int32(simState.height) && width > 0 && height > 0

			if isValidCamSize {
				const maxPayloadSize int32 = 65000

				// Pack the frame data into a temporary buffer first
				packedFrameBuf := new(bytes.Buffer)
				packedFrameBuf.Grow(int((width*height + 7) / 8))

				for row := int32(0); row < height; row++ {
					yOffset := (y + row) * int32(simState.width)
					for col := int32(0); col < width; col += 8 {
						fullBufferIndex := yOffset + (x + col)
						chunk := frameBuffer[fullBufferIndex : fullBufferIndex+8]
						key := BytesToUint64Unsafe(chunk)
						packedByte, ok := uint64ToByteLUT[key]
						if !ok {
							packedByte = 0
						}
						packedFrameBuf.WriteByte(packedByte)
					}
				}

				dataToSend := packedFrameBuf.Bytes()
				totalSize := int32(len(dataToSend))
				var sentBytes int32 = 0
				var chunkIndex byte = 0

				// The payload now includes the opcode, frame ID, and chunk index
				payloadSize := maxPayloadSize - 6 // 1 byte for OpCode, 4 for frame ID, 1 for chunk index

				for sentBytes < totalSize {
					end := sentBytes + payloadSize
					if end > totalSize {
						end = totalSize
					}

					chunkData := dataToSend[sentBytes:end]

					fragmentedData := new(bytes.Buffer)
					fragmentedData.WriteByte(OpCodeFrame)
					binary.Write(fragmentedData, binary.LittleEndian, currentFrameID)
					fragmentedData.WriteByte(chunkIndex)
					fragmentedData.Write(chunkData)

					if err := dc.Send(fragmentedData.Bytes()); err != nil {
						log.Printf("Error sending data chunk on data channel: %v", err)
					}

					sentBytes = end
					chunkIndex++
				}
			}

			// The code for sending `OpCodeSimData` is independent and can remain as is.
			camMessage := new(bytes.Buffer)
			camMessage.WriteByte(OpCodeSimData)
			binary.Write(camMessage, binary.LittleEndian, x)
			binary.Write(camMessage, binary.LittleEndian, y)
			binary.Write(camMessage, binary.LittleEndian, simState.width)
			binary.Write(camMessage, binary.LittleEndian, simState.height)
			if err := dc.Send(camMessage.Bytes()); err != nil {
				log.Printf("Error sending camera data on data channel: %v", err)
			}
		}
		peerConnectionsMu.Unlock()
		pool.Put(frame)
	}
}

func findPeerConnectionIndex(pc *webrtc.PeerConnection) int {
	for i, p := range peerConnections {
		if p == pc {
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
		var gravPower = job.simState.dt * 6
		var pullDist float32 = 936000

		for i := job.startIndex; i < job.endIndex; i++ {
			p := &particles[i]

			for _, input := range validInputs {
				dirx := input.X - p.x
				diry := input.Y - p.y
				dist := dirx*dirx + diry*diry
				if dist < pullDist && dist > 1 {
					var grav = 3 / float32(math.Sqrt(float64(dist)))
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
					if idx < uint32(len(frame)) {
						frame[idx] = 1
					}
				}
			}
		}
		wg.Done()
	}
}

func main() {
	m := &webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		log.Fatalf("Failed to register default codecs: %v", err)
	}

	api = webrtc.NewAPI(webrtc.WithMediaEngine(m))

	go startSim()
	http.HandleFunc("/webrtc", webrtcHandler)
	http.Handle("/", http.FileServer(http.Dir("./public")))

	var port = 41069
	log.Println(fmt.Sprintf("Server started on :%d", port))
	if err := http.ListenAndServe(fmt.Sprintf(":%d", port), nil); err != nil {
		log.Fatal("ListenAndServe:", err)
	}
}
