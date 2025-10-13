import SwiftUI
import MetalKit
import GameplayKit
import simd
import Foundation

struct QuadVertex {
    var position: SIMD2<Float>
    var texCoord: SIMD2<Float>
}

class MetalRenderer: NSObject, MTKViewDelegate {
    let device: MTLDevice
    let commandQueue: MTLCommandQueue
    
    var textures: [MTLTexture?] = [nil, nil]
    var pixelBuffers: [MTLBuffer?] = [nil, nil]
    var currentBufferIndex = 0
    
    var pipelineState: MTLRenderPipelineState?
    var vertexBuffer: MTLBuffer?
    
    var positions: UnsafeMutablePointer<SIMD2<Float>>?
    var velocities: UnsafeMutablePointer<SIMD2<Float>>?
    var starts: UnsafeMutablePointer<SIMD2<Float>>?
    var threadPixelBuffers: [UnsafeMutablePointer<UInt8>] = []
    
    let particleCount = 10_000_000

    var width: Int
    var height: Int
    var invScale = 1
    var lastFrameTime: CFAbsoluteTime
    var frameCount: Int
    
    // New: Two attraction points
    var attractionPoint1: SIMD2<Float>?
    var attractionPoint2: SIMD2<Float>?
    let respawnRadiusSquared: Float = 50.0 // Squared distance for respawn

    let numberOfThreads: Int

    init?(mtkView: MTKView) {
        guard let device = MTLCreateSystemDefaultDevice() else { return nil }
        self.device = device
        self.lastFrameTime = CFAbsoluteTimeGetCurrent()
        self.commandQueue = device.makeCommandQueue()!
        self.width = max(4, Int(mtkView.drawableSize.width))
        self.height = max(4, Int(mtkView.drawableSize.height))
        self.frameCount = 0
        
        self.positions = UnsafeMutablePointer<SIMD2<Float>>.allocate(capacity: particleCount)
        self.velocities = UnsafeMutablePointer<SIMD2<Float>>.allocate(capacity: particleCount)
        self.starts = UnsafeMutablePointer<SIMD2<Float>>.allocate(capacity: particleCount)
        
        self.positions?.initialize(repeating: SIMD2(0,0), count: particleCount)
        self.velocities?.initialize(repeating: SIMD2(0,0), count: particleCount)
        self.starts?.initialize(repeating: SIMD2(0,0), count: particleCount)
        
        self.numberOfThreads = ProcessInfo.processInfo.activeProcessorCount
        super.init()
        
        mtkView.device = device
        mtkView.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        mtkView.colorPixelFormat = .rgba8Unorm
        mtkView.delegate = self
        mtkView.isPaused = false

        createTextureAndBuffer(width: self.width, height: self.height)
        setupRenderPipeline(view: mtkView)
    }

    deinit {
        positions?.deinitialize(count: particleCount)
        positions?.deallocate()
        velocities?.deinitialize(count: particleCount)
        velocities?.deallocate()
        starts?.deinitialize(count: particleCount)
        starts?.deallocate()

        for ptr in threadPixelBuffers {
            ptr.deinitialize(count: width * height)
            ptr.deallocate()
        }
    }
    
    func setupRenderPipeline(view: MTKView) {
        let shaderSource = """
        #include <metal_stdlib>
        using namespace metal;

        struct QuadVertex {
            float2 position [[attribute(0)]];
            float2 texCoord [[attribute(1)]];
        };

        struct VertexOut {
            float4 position [[position]];
            float2 texCoord;
        };

        vertex VertexOut vertex_main(QuadVertex in [[stage_in]]) {
            VertexOut out;
            out.position = float4(in.position, 0.0, 1.0);
            out.texCoord = in.texCoord;
            return out;
        }
        
        float3 hsl2rgb(float h, float s, float l) {
            float c = (1.0 - fabs(2.0 * l - 1.0)) * s;
            float hp = h * 6.0;
            float x = c * (1.0 - fabs(fmod(hp, 2.0) - 1.0));
            float3 rgb1;
            
            if (0.0 <= hp && hp < 1.0) rgb1 = float3(c, x, 0.0);
            else if (1.0 <= hp && hp < 2.0) rgb1 = float3(x, c, 0.0);
            else if (2.0 <= hp && hp < 3.0) rgb1 = float3(0.0, c, x);
            else if (3.0 <= hp && hp < 4.0) rgb1 = float3(0.0, x, c);
            else if (4.0 <= hp && hp < 5.0) rgb1 = float3(x, 0.0, c);
            else if (5.0 <= hp && hp < 6.0) rgb1 = float3(c, 0.0, x);
            else rgb1 = float3(0.0, 0.0, 0.0);

            float m = l - 0.5 * c;
            return rgb1 + m;
        }

        fragment float4 fragment_main(VertexOut in [[stage_in]],
                                      texture2d<float> imageTexture [[texture(0)]]) {
            constexpr sampler s(address::clamp_to_edge, filter::nearest);
            float pixelValueNormalized = imageTexture.sample(s, in.texCoord).r;
            int pixelValue = int(pixelValueNormalized * 255.0);

            float3 color = float3(0.0, 0.0, 0.0);

            if (pixelValue > 0) {
                return float4(in.texCoord.x, in.texCoord.y, 1.0 - in.texCoord.x, 1.0)*(pixelValueNormalized*20);
            }

            return float4(color, 1.0);
        }
        """

        do {
            let library = try device.makeLibrary(source: shaderSource, options: nil)
            let vertexFunction = library.makeFunction(name: "vertex_main")
            let fragmentFunction = library.makeFunction(name: "fragment_main")

            guard let vertFunc = vertexFunction, let fragFunc = fragmentFunction else { return }

            let pipelineDescriptor = MTLRenderPipelineDescriptor()
            pipelineDescriptor.vertexFunction = vertFunc
            pipelineDescriptor.fragmentFunction = fragFunc
            pipelineDescriptor.colorAttachments[0].pixelFormat = view.colorPixelFormat

            let vertexDescriptor = MTLVertexDescriptor()
            vertexDescriptor.attributes[0].format = .float2
            vertexDescriptor.attributes[0].offset = 0
            vertexDescriptor.attributes[0].bufferIndex = 0
            vertexDescriptor.attributes[1].format = .float2
            vertexDescriptor.attributes[1].offset = MemoryLayout<SIMD2<Float>>.stride
            vertexDescriptor.attributes[1].bufferIndex = 0

            vertexDescriptor.layouts[0].stride = MemoryLayout<QuadVertex>.stride
            vertexDescriptor.layouts[0].stepFunction = .perVertex

            pipelineDescriptor.vertexDescriptor = vertexDescriptor

            pipelineState = try device.makeRenderPipelineState(descriptor: pipelineDescriptor)

            let vertices: [QuadVertex] = [
                QuadVertex(position: [-1.0, -1.0], texCoord: [0.0, 1.0]),
                QuadVertex(position: [ 1.0, -1.0], texCoord: [1.0, 1.0]),
                QuadVertex(position: [-1.0,  1.0], texCoord: [0.0, 0.0]),
                
                QuadVertex(position: [-1.0,  1.0], texCoord: [0.0, 0.0]),
                QuadVertex(position: [ 1.0, -1.0], texCoord: [1.0, 1.0]),
                QuadVertex(position: [ 1.0,  1.0], texCoord: [1.0, 0.0])
            ]
            vertexBuffer = device.makeBuffer(bytes: vertices, length: MemoryLayout<QuadVertex>.stride * vertices.count, options: [])

        } catch {
            print("Failed to create render pipeline state or compile shader: \(error)")
        }
    }

    func createTextureAndBuffer(width: Int, height: Int) {
        self.width = width / invScale
        self.height = height / invScale
        
        let bytesPerPixel = 1
        let alignment = 16
        var rowBytes = self.width * bytesPerPixel
        rowBytes = (rowBytes + alignment - 1) & ~(alignment - 1)
        var length = self.height * rowBytes
        length = (length + alignment - 1) & ~(alignment - 1)

        let textureDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .r8Unorm,
            width: self.width,
            height: self.height,
            mipmapped: false
        )
        textureDescriptor.usage = [.shaderRead, .renderTarget]
        textureDescriptor.storageMode = .shared
        
        for i in 0..<2 {
            pixelBuffers[i] = device.makeBuffer(length: length, options: [.storageModeShared])
            textures[i] = pixelBuffers[i]?.makeTexture(descriptor: textureDescriptor, offset: 0, bytesPerRow: rowBytes)
        }
        
        let bufferSize = self.width * self.height
        for ptr in threadPixelBuffers {
            ptr.deinitialize(count: bufferSize)
            ptr.deallocate()
        }
        threadPixelBuffers.removeAll()
        
        for _ in 0..<numberOfThreads {
            let ptr = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
            ptr.initialize(repeating: 0, count: bufferSize)
            threadPixelBuffers.append(ptr)
        }
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        createTextureAndBuffer(width: Int(size.width), height: Int(size.height))
        setParticlePositions(count: self.particleCount)
        // Update attraction points when screen size changes
        if let currentPullTarget = self.attractionPoint1 {
            handleTouch(CGPoint(x: CGFloat(currentPullTarget.x), y: CGFloat(currentPullTarget.y)))
        }
    }

    func draw(in view: MTKView) {
        let currentTime = CFAbsoluteTimeGetCurrent()
        let deltaTime = currentTime - lastFrameTime
        lastFrameTime = currentTime
        frameCount += 1
        
        if frameCount % 10 == 0 {
            print("FPS: \(String(format: "%.2f", 1/deltaTime))")
            print(self.width, self.height)
        }

        tick(dt: Float(deltaTime))
        
        guard let drawable = view.currentDrawable,
              let renderPassDescriptor = view.currentRenderPassDescriptor,
              let textureToDisplay = textures[currentBufferIndex] else { return }

        renderPassDescriptor.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)
        renderPassDescriptor.colorAttachments[0].loadAction = .clear
        renderPassDescriptor.colorAttachments[0].storeAction = .store

        let commandBuffer = commandQueue.makeCommandBuffer()
        
        if let renderEncoder = commandBuffer?.makeRenderCommandEncoder(descriptor: renderPassDescriptor) {
            renderEncoder.setRenderPipelineState(pipelineState!)
            renderEncoder.setVertexBuffer(vertexBuffer, offset: 0, index: 0)
            renderEncoder.setFragmentTexture(textureToDisplay, index: 0)
            renderEncoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6)
            renderEncoder.endEncoding()
        }

        commandBuffer?.present(drawable)
        commandBuffer?.commit()
        
        currentBufferIndex = (currentBufferIndex + 1) % 2
    }
    
    func tick(dt: Float) {
        guard let positionPtr = positions,
              let startsPtr = starts,
              let velocityPtr = velocities else { return }
        
        guard let bufferPointer = pixelBuffers[currentBufferIndex]?.contents() else { return }
        
        let pixelDataPtr = bufferPointer.assumingMemoryBound(to: UInt8.self)
        pixelDataPtr.initialize(repeating: 0, count: width * height)

        let fw = Float(width)
        let fh = Float(height)
        let iw = self.width
        let fw_minus_1 = fw - 1.0
        let fh_minus_1 = fh - 1.0
        
        let currentPullTarget = self.attractionPoint1 ?? SIMD2(0,0)
        let hasAttraction = self.attractionPoint1 != nil
        let attractionStrength: Float32 = 2600 * dt
        let friction: Float32 = pow(0.99, dt * 60.0)
        let numParticles = self.particleCount
        let particlesPerThread = (numParticles + numberOfThreads - 1) / numberOfThreads
        
//        let randomNumberSource = GKLinearCongruentialRandomSource()


        DispatchQueue.concurrentPerform(iterations: numberOfThreads) { threadIndex in
            let localPtr = threadPixelBuffers[threadIndex]
            localPtr.initialize(repeating: 0, count: width * height)

            var i = threadIndex * particlesPerThread
            let endIndex = min(i + particlesPerThread, numParticles)
            let bounds = SIMD2<Float>(fw, fh)
            let invBounds = 1.0 / bounds
            
            while i < endIndex {
                var currentPosition = positionPtr[i]
                var currentVelocity = velocityPtr[i]
                let origin = startsPtr[i]

                if hasAttraction {
                    let diff = currentPullTarget - currentPosition
                    let distanceSquared = length_squared(diff)

//                    if distanceSquared < respawnRadiusSquared {
//                        // Respawn particle at point2 with random velocity
//                        
//                        let angle = randomNumberSource.nextUniform() * 2.0 * .pi
//                        currentPosition = point2
//                        currentVelocity = SIMD2(cos(angle), sin(angle)) * 500
                    if distanceSquared > 1.0 {
                        // Apply attraction
                        let distance = sqrt(distanceSquared)
                        let attractionVector = (diff / distance) * attractionStrength
                        currentVelocity += attractionVector
                    }
                }
                
//                let diff = origin - currentPosition
//                let distanceSquared = length_squared(diff)
//                if distanceSquared > 1.0 {
//                    let distance = sqrt(distanceSquared)
//                    let attractionVector = (diff / distance) * 10.0
//                    currentVelocity += attractionVector
//                }
//                if distanceSquared > 100.0 {
//                    let distance = sqrt(distanceSquared)
//                    let attractionVector = (diff / distance) * 10.0
//                    currentVelocity += attractionVector
//                }
                
                currentPosition += currentVelocity * dt
                currentVelocity *= friction
                currentPosition -= floor(currentPosition * invBounds) * bounds
                positionPtr[i] = currentPosition
                velocityPtr[i] = currentVelocity
                
                
                let clampedX = Int(max(0.0, min(fw_minus_1, currentPosition.x)))
                let clampedY = Int(max(0.0, min(fh_minus_1, currentPosition.y)))
                let index = (clampedY * iw + clampedX)
                localPtr[index] = min(254, localPtr[index] + 1)
                
                i += 1
            }
        }

        
        // can commit this out if updating pixelDataPtr above
        let pixelCount = width * height
        let pixelsPerThread = (pixelCount + numberOfThreads - 1) / numberOfThreads

        DispatchQueue.concurrentPerform(iterations: numberOfThreads) { threadIndex in
            let start = threadIndex * pixelsPerThread
            let end = min(start + pixelsPerThread, pixelCount)

            for i in start..<end {
                var sum = 0;
                for localThreadIndex in 0..<numberOfThreads {
                    let count = threadPixelBuffers[localThreadIndex][i]
                    sum = sum + Int(count)
                }
                pixelDataPtr[i] = UInt8(min(255, sum))
            }
        }
    }
    
    func randomizeParticles(count: Int) {
        if frameCount > 10 {
            return
        }

        guard let positionPtr = positions,
              let velocityPtr = velocities else { return }

        let randomNumberSource = GKMersenneTwisterRandomSource()

        for i in 0..<count {
            positionPtr[i].x = Float(randomNumberSource.nextInt(upperBound: width))
            positionPtr[i].y = Float(randomNumberSource.nextInt(upperBound: height))

            velocityPtr[i].x = Float(randomNumberSource.nextUniform() * 6.0 - 3.0)
            velocityPtr[i].y = Float(randomNumberSource.nextUniform() * 6.0 - 3.0)
        }
    }
    
    func setParticlePositions(count: Int) {
        if frameCount > 20 {
            return
        }

        guard let positionPtr = positions,
              let startPtr = starts,
              let velocityPtr = velocities else { return }

        let randomNumberSource = GKMersenneTwisterRandomSource()
        let paletteSize = 5
        let clustersPerColor = 150
        
        let minRadius: Float = 100.0
        let maxRadius: Float = 300.0

        var clusterData: [[(center: CGPoint, radius: Float)]] = Array(repeating: [], count: paletteSize)

        for colorType in 0..<paletteSize {
            for _ in 0..<clustersPerColor {
                let randomX = Float(randomNumberSource.nextInt(upperBound: width))
                let randomY = Float(randomNumberSource.nextInt(upperBound: height))
                
                let randomRadius = minRadius + (maxRadius - minRadius) * randomNumberSource.nextUniform()
                
                clusterData[colorType].append((center: CGPoint(x: CGFloat(randomX), y: CGFloat(randomY)), radius: randomRadius))
            }
        }

        let spiralTurns: Float = 6.0

        for i in 0..<count {
            let colorType = i % paletteSize
            let selectedClusterIndex = randomNumberSource.nextInt(upperBound: clustersPerColor)
            
            let cluster = clusterData[colorType][selectedClusterIndex]
            let center = cluster.center
            let radius = cluster.radius

            let maxAngle = spiralTurns * 2.0 * .pi
            let b = radius / maxAngle

            let t = Float(i) / Float(count)
            let angle = t * maxAngle
            let dist = b * angle

            let newX = Float(center.x) + dist * cos(angle)
            let newY = Float(center.y) + dist * sin(angle)

//            newX = max(0.0, min(newX, Float(width) - 1.0))
//            newY = max(0.0, min(newY, Float(height) - 1.0))

            positionPtr[i].x = newX
            positionPtr[i].y = newY
            
            startPtr[i].x = newX
            startPtr[i].y = newY

            // uncomment this if you want the particles to have little movement
            velocityPtr[i].x = 0 // Float(randomNumberSource.nextUniform() * 6.0 - 3.0)
            velocityPtr[i].y = 0 // Float(randomNumberSource.nextUniform() * 6.0 - 3.0)
        }
    }
    
    func handleTouch(_ point: CGPoint?) {
        if let unwrappedPoint = point {
            let scaledX = unwrappedPoint.x / CGFloat(self.invScale)
            let scaledY = unwrappedPoint.y / CGFloat(self.invScale)
            
            self.attractionPoint1 = SIMD2<Float>(Float(scaledX), Float(scaledY))
            
            // Calculate the inverse point
            // Assuming the inversion is relative to the center of the view
            let centerX = Float(self.width) / 2.0
            let centerY = Float(self.height) / 2.0
            
            self.attractionPoint2 = SIMD2<Float>(centerX - (Float(scaledX) - centerX), centerY - (Float(scaledY) - centerY))
            
        } else {
            self.attractionPoint1 = nil
            self.attractionPoint2 = nil
        }
    }
}

struct MetalView: View {
    var body: some View {
        #if os(iOS)
        iOSMetalView()
        #elseif os(macOS)
        macOSMetalView()
        #else
        Text("Metal is not supported on this platform.")
        #endif
    }
}

#if os(iOS)
private struct iOSMetalView: UIViewRepresentable {
    func makeUIView(context: Context) -> MTKView {
        let mtkView = MTKView()
        mtkView.device = MTLCreateSystemDefaultDevice()
        mtkView.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        mtkView.colorPixelFormat = .rgba8Unorm
        mtkView.delegate = context.coordinator
        
        mtkView.isPaused = false
        
        context.coordinator.renderer = MetalRenderer(mtkView: mtkView)

        let pressGesture = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePress(_:)))
        pressGesture.minimumPressDuration = 0
        mtkView.addGestureRecognizer(pressGesture)

        return mtkView
    }

    func updateUIView(_ uiView: MTKView, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    class Coordinator: NSObject, MTKViewDelegate {
        var renderer: MetalRenderer?

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
            renderer?.mtkView(view, drawableSizeWillChange: size)
        }
        
        func draw(in view: MTKView) {
            renderer?.draw(in: view)
        }
        
        @objc func handlePress(_ gesture: UILongPressGestureRecognizer) {
            guard let view = gesture.view else { return }
            
            switch gesture.state {
            case .began, .changed:
                let location = gesture.location(in: view)
                // The current iOS coordinate system might be flipped vertically compared to Metal's default.
                // You might need to adjust this based on your specific setup.
                // If particles are attracted to the wrong vertical position, remove the `view.bounds.height -`
                let adjustedLocation = CGPoint(x: location.x * view.contentScaleFactor, y: (view.bounds.height - location.y) * view.contentScaleFactor)
                renderer?.handleTouch(adjustedLocation)
            case .ended, .cancelled:
                renderer?.handleTouch(nil)
            default:
                break
            }
        }
    }
}
#elseif os(macOS)
private struct macOSMetalView: NSViewRepresentable {
    func makeNSView(context: Context) -> MTKView {
        let mtkView = MTKView()
        mtkView.device = MTLCreateSystemDefaultDevice()
        mtkView.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        mtkView.colorPixelFormat = .rgba8Unorm
        mtkView.delegate = context.coordinator
    
        
        mtkView.isPaused = false

        context.coordinator.renderer = MetalRenderer(mtkView: mtkView)

        let pressGesture = NSPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePress(_:)))
        pressGesture.minimumPressDuration = 0
        mtkView.addGestureRecognizer(pressGesture)
        
        return mtkView
    }

    func updateNSView(_ nsView: MTKView, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    class Coordinator: NSObject, MTKViewDelegate {
        var renderer: MetalRenderer?

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
            renderer?.mtkView(view, drawableSizeWillChange: size)
        }
        
        func draw(in view: MTKView) {
            renderer?.draw(in: view)
        }
        
        @objc func handlePress(_ gesture: NSPressGestureRecognizer) {
            guard let view = gesture.view else { return }
            
            switch gesture.state {
            case .began, .changed:
                let location = gesture.location(in: view)
                // macOS has a flipped Y-axis compared to UIKit for mouse events
                let flippedLocation = CGPoint(x: location.x * view.window!.backingScaleFactor, y: (view.bounds.height - location.y) * view.window!.backingScaleFactor)
                renderer?.handleTouch(flippedLocation)
            case .ended, .cancelled:
                renderer?.handleTouch(nil)
            default:
                break
            }
        }
    }
}
#endif

struct ContentView: View {
    var body: some View {
        GeometryReader { geometry in
            MetalView()
                .frame(width: geometry.size.width, height: geometry.size.height)
        }
    }
}

#Preview {
    ContentView()
}
