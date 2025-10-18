import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import static jdk.incubator.vector.VectorOperators.*;

public class ParticleSim {

    public static void main(String[] args) {
        new ParticleSim().createAndShowGUI();
    }

    private void createAndShowGUI() {
        JFrame frame = new JFrame("Sips Java");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        int width = 1200;
        int height = 800;

        ParticlePanel particlePanel = new ParticlePanel(width, height);
        frame.add(particlePanel);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        particlePanel.startSimulation();
    }
}

class ParticlePanel extends JPanel
        implements MouseListener, MouseMotionListener, ComponentListener, KeyListener {

    public final float PULL_FORCE = 800f;
    public final float MIN_PULL_DIST = 1.0f;
    public final float FRICTION = 0.9f;

    public static final int NUM_PARTICLES = 50_000_000;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final VectorSpecies<Float> F_SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final int LANE_SIZE = F_SPECIES.length();
    private final ExecutorService executorService = Executors.newFixedThreadPool(CPU_COUNT);
    private final ParticleUpdateTask[] tasks = new ParticleUpdateTask[CPU_COUNT];
    private final Set<Character> keysPressed = new HashSet<>();
    private Map<Character, Point> velInputMap = Map.of(
            'a', new Point(1, 0),
            'd', new Point(-1, 0),
            's', new Point(0, -1),
            'w', new Point(0, 1));

    public float[] positionsX = new float[NUM_PARTICLES];
    public float[] positionsY = new float[NUM_PARTICLES];
    public float[] velocitiesX = new float[NUM_PARTICLES];
    public float[] velocitiesY = new float[NUM_PARTICLES];
    // private float[] startX = new float[NUM_PARTICLES];
    // private float[] startY = new float[NUM_PARTICLES];
    public int[] colors = new int[NUM_PARTICLES];

    public BufferedImage image;
    // private byte[] pixelArray;
    private int width;
    private int height;
    // private byte[][] threadPixelBuffers;
    public int[][] threadPixelBuffers;

    public Point mousePosition = new Point(0, 0);
    public boolean isMousePressed = false;

    private volatile boolean running = false;
    private Thread gameLoopThread;
    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final double TARGET_FPS = 120.0;
    private static final double NS_PER_TICK = NS_PER_SECOND / TARGET_FPS;
    private boolean isResizeRequested = false;
    private boolean isResetRequested = false;
    private boolean isSlowDownRequested = false;
    final int resetSquareType = 1;
    final int resetSquareMultiType = 2;
    final int resetCircleType = 3;
    final int resetImageType = 4;
    private int resetType = 0;
    private boolean isPanning = false;
    public volatile Point panDeltaInput = new Point(0, 0);
    public volatile float inputVelScale = 0.2f;
    private boolean shouldReturnToStart = false;

    private long lastTickTime;
    private int frames = 0;

    public ParticlePanel(int width, int height) {
        setSize(width, height);
        setPreferredSize(new Dimension(width, height));
        this.handleResize(width, height);

        // create tasks
        var i = 0;
        while (i < CPU_COUNT) {
            tasks[i] = new ParticleUpdateTask();
            i++;
        }

        placeParticlesSquare();
        addMouseListener(this);
        addMouseMotionListener(this);
        addComponentListener(this);
        addKeyListener(this);
        setBackground(Color.BLACK);
        setFocusable(true);
        requestFocusInWindow();
        setIgnoreRepaint(true);

        lastTickTime = System.nanoTime();
        frames = 0;
    }

    private long xorshiftState = 1;

    private float fastRandomFloat() {
        final float INT_TO_UNIT = 1.0f / 4294967296.0f;
        xorshiftState ^= (xorshiftState << 13);
        xorshiftState ^= (xorshiftState >>> 17);
        xorshiftState ^= (xorshiftState << 5);

        return (xorshiftState & 0xFFFFFFFFL) * INT_TO_UNIT;
    }

    public void startSimulation() {
        if (!running) {
            running = true;
            gameLoopThread = new Thread(this::gameLoop);
            gameLoopThread.start();
        }
    }

    private void gameLoop() {
        lastTickTime = System.nanoTime();

        while (running) {
            long now = System.nanoTime();
            long timeElapsed = now - lastTickTime;
            this.processInputRequests();

            if (timeElapsed >= NS_PER_TICK) {
                float deltaTime = timeElapsed / (float) NS_PER_SECOND;
                lastTickTime = now;

                for (var key : this.keysPressed) {
                    float speed = 500;
                    if (this.velInputMap.containsKey(key)) {
                        this.panDeltaInput.x += velInputMap.get(key).x * speed * deltaTime;
                        this.panDeltaInput.y += velInputMap.get(key).y * speed * deltaTime;
                    }
                }

                long tickStart = System.nanoTime();
                tick(deltaTime);
                long tickEnd = System.nanoTime();
                long tickDuration = (tickEnd - tickStart);

                long renderStart = System.nanoTime();
                render();

                Graphics2D g = (Graphics2D) getGraphics();
                g.drawImage(image, 0, 0, this);
                g.dispose();
                Toolkit.getDefaultToolkit().sync();
                frames++;

                long renderEnd = System.nanoTime();
                long renderDuration = (renderEnd - renderStart);

                if (frames % TARGET_FPS == 0) {
                    float tickDurationMs = tickDuration / 1_000_000.0f;
                    float renderDurationMs = renderDuration / 1_000_000.0f;
                    System.out.printf("(Tick): %.3f ms\n", tickDurationMs);
                    System.out.printf("(Render): %.3f ms\n", renderDurationMs);
                    System.out.printf("(Total): %.3f ms\n", tickDurationMs + renderDurationMs);
                }
            } else {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Game loop interrupted.");
                    running = false;
                }
            }
        }
    }

    private void tick(float deltaTime) {
        final int w = this.width;
        final int h = this.height;

        final int vectorizedEndIndex = (NUM_PARTICLES / LANE_SIZE) * LANE_SIZE;
        final int chunkSize = vectorizedEndIndex / CPU_COUNT;
        final var futures = new ArrayList<Future<?>>(CPU_COUNT);

        // safe input data
        final int panDx = this.panDeltaInput.x;
        final int panDy = this.panDeltaInput.y;
        final float vScale = this.isSlowDownRequested ? this.inputVelScale : 0f;

        // only reset if there was a change.
        this.panDeltaInput.x = 0;
        this.panDeltaInput.y = 0;

        for (int i = 0; i < CPU_COUNT; i++) {
            int start = i * chunkSize;
            int end = (i == CPU_COUNT - 1) ? vectorizedEndIndex : start + chunkSize;
            ParticleUpdateTask task = tasks[i];
            task.updateParams(i, start, end, this, deltaTime, panDx, panDy, vScale);
            futures.add(executorService.submit(task));
        }

        this.isSlowDownRequested = false;

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    private void render() {
        int[] buff = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        Arrays.fill(buff, 0);
        final int PIXEL_COUNT = buff.length;
        IntStream.range(0, CPU_COUNT).parallel().forEach(chunkIndex -> {
            int chunkSize = PIXEL_COUNT / CPU_COUNT;
            int start = chunkIndex * chunkSize;
            int end = (chunkIndex == CPU_COUNT - 1) ? PIXEL_COUNT : start + chunkSize;

            for (int i = start; i < end; i++) {
                int color = 0;

                for (int localIndex = 0; localIndex < CPU_COUNT; localIndex++) {
                    int col = threadPixelBuffers[localIndex][i];
                    if (col != 0) {
                        color = col;
                        break;
                    }
                }
                buff[i] = (0xFF << 24) | color;
            }
        });
    }

    private void processInputRequests() {
        if (this.isResizeRequested) {
            this.isResizeRequested = false;
            this.handleResize(getWidth(), getHeight());
        }
        if (this.isResetRequested) {
            this.isResetRequested = false;
            if (resetType == resetSquareType) {
                placeParticlesSquare();
            }
            if (resetType == resetSquareMultiType) {
                placeParticlesSquareMulti();
            }
            if (resetType == resetCircleType) {
                placeParticlesCircle();
            }
            if (resetType == resetImageType) {
                placeParticlesAsImage();
            }
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON1) {
            isMousePressed = true;
        }
        if (e.getButton() == MouseEvent.BUTTON3) {
            isPanning = true;
        }
        mousePosition = e.getPoint();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        isMousePressed = false;
        isPanning = false;
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        Point current = e.getPoint();
        if (isPanning) {
            int dx = current.x - mousePosition.x;
            int dy = current.y - mousePosition.y;
            panDeltaInput.x += dx;
            panDeltaInput.y += dy;
        }

        mousePosition = current;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }

    @Override
    public void mouseMoved(MouseEvent e) {
    }

    @Override
    public void componentResized(ComponentEvent e) {
        this.isResizeRequested = true;
    }

    private void handleResize(int w, int h) {
        this.width = w;
        this.height = h;
        this.setSize(w, h);
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        threadPixelBuffers = new int[CPU_COUNT][];
        for (int i = 0; i < CPU_COUNT; i++) {
            threadPixelBuffers[i] = new int[w * h];
        }
    }

    @Override
    public void componentMoved(ComponentEvent e) {
    }

    @Override
    public void componentShown(ComponentEvent e) {
    }

    @Override
    public void componentHidden(ComponentEvent e) {
    }

    public int calculateOklabColor(float L, float a, float b) {
        float Lp = L + 0.3963377774f * a + 0.2158037573f * b;
        float ap = L - 0.1055613423f * a + 0.0782353724f * b;
        float bp = L - 0.3081758091f * a - 1.0732513936f * b;
        float l = Lp * Lp * Lp;
        float m = ap * ap * ap;
        float s = bp * bp * bp;
        float R_linear = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
        float G_linear = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
        float B_linear = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;
        float R_nonlinear;
        if (R_linear <= 0.0031308f) {
            R_nonlinear = R_linear * 12.92f;
        } else {
            R_nonlinear = (float) (1.055 * Math.pow(R_linear, 1.0f / 2.4f) - 0.055);
        }

        float G_nonlinear;
        if (G_linear <= 0.0031308f) {
            G_nonlinear = G_linear * 12.92f;
        } else {
            G_nonlinear = (float) (1.055 * Math.pow(G_linear, 1.0f / 2.4f) - 0.055);
        }

        float B_nonlinear;
        if (B_linear <= 0.0031308f) {
            B_nonlinear = B_linear * 12.92f;
        } else {
            B_nonlinear = (float) (1.055 * Math.pow(B_linear, 1.0f / 2.4f) - 0.055);
        }

        int r = (int) (R_nonlinear * 255.0f);
        int g = (int) (G_nonlinear * 255.0f);
        int b_val = (int) (B_nonlinear * 255.0f);

        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b_val = Math.max(0, Math.min(255, b_val));

        return (0xFF << 24) | (r << 16) | (g << 8) | b_val;
    }

    @Override
    public void keyTyped(KeyEvent e) {
        if (e.getKeyChar() == ' ') {
            this.isSlowDownRequested = true;
        }
        if (e.getKeyChar() == '2') {
            this.isResetRequested = true;
            this.resetType = resetSquareMultiType;
        }
        if (e.getKeyChar() == '1') {
            this.isResetRequested = true;
            this.resetType = resetSquareType;
        }
        if (e.getKeyChar() == '3') {
            this.isResetRequested = true;
            this.resetType = resetCircleType;
        }
        if (e.getKeyChar() == '4') {
            this.isResetRequested = true;
            this.resetType = resetImageType;
        }
        if (e.getKeyChar() == 'r') {
            this.shouldReturnToStart = !this.shouldReturnToStart;
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        keysPressed.add(e.getKeyChar());
    }

    @Override
    public void keyReleased(KeyEvent e) {
        keysPressed.remove(e.getKeyChar());
    }

    private void placeParticlesSquare() {
        final float centerX = this.width / 2.0f;
        final float centerY = this.height / 2.0f;

        final float L_CONSTANT = 0.7f;
        final float C_CONSTANT = 0.25f;

        for (int i = 0; i < NUM_PARTICLES; i++) {
            positionsX[i] = (float) this.width * fastRandomFloat();
            positionsY[i] = (float) this.height * fastRandomFloat();
            // startX[i] = positionsX[i];
            // startY[i] = positionsY[i];
            velocitiesX[i] = 0;
            velocitiesY[i] = 0;

            float dx = positionsX[i] - centerX;
            float dy = positionsY[i] - centerY;
            double angleRadians = Math.atan2(dy, dx);
            float h = (float) ((angleRadians + Math.PI) / (2.0 * Math.PI));
            float a = (float) (C_CONSTANT * Math.cos(angleRadians));
            float b = (float) (C_CONSTANT * Math.sin(angleRadians));
            colors[i] = calculateOklabColor(L_CONSTANT, a, b);
        }
    }

    private void placeParticlesCircle() {
        final float centerX = this.width / 2.0f;
        final float centerY = this.height / 2.0f;

        final float L_CONSTANT = 0.7f;
        final float C_CONSTANT = 0.25f;
        final float radius = Math.min(this.width, this.height) / 2;

        for (int i = 0; i < NUM_PARTICLES; i++) {
            var d = fastRandomFloat() * radius;
            var angle = fastRandomFloat() * 2 * Math.PI;
            var cosA = (float) Math.cos(angle);
            var sinA = (float) Math.sin(angle);
            positionsX[i] = cosA * d + centerX;
            positionsY[i] = sinA * d + centerY;
            // startX[i] = positionsX[i];
            // startY[i] = positionsY[i];
            velocitiesX[i] = 0;
            velocitiesY[i] = 0;

            float a = (float) (C_CONSTANT * cosA);
            float b = (float) (C_CONSTANT * sinA);
            colors[i] = calculateOklabColor(L_CONSTANT, a, b);
        }
    }

    private void placeParticlesAsImage() {
        float width = this.width;
        float height = this.height;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setDialogTitle("Select Image Input File");
                FileNameExtensionFilter filter = new FileNameExtensionFilter(
                        "Image Files (JPG, PNG, GIF)", "jpg", "jpeg", "png", "gif");
                fileChooser.setFileFilter(filter);
                int userSelection = fileChooser.showOpenDialog(null);

                if (userSelection != JFileChooser.APPROVE_OPTION) {
                    return;
                }
                File selectedFile = fileChooser.getSelectedFile();
                if (selectedFile == null || !selectedFile.exists()) {
                    return;
                }

                try {
                    BufferedImage sourceImage = ImageIO.read(selectedFile);
                    if (sourceImage == null) {
                        JOptionPane.showMessageDialog(
                                null,
                                "Error: File is not a valid image format.",
                                "Loading Error",
                                JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    final int N = NUM_PARTICLES;
                    final int particleGridSide = (int) Math.floor(Math.sqrt(N));
                    final int sourceW = sourceImage.getWidth();
                    final int sourceH = sourceImage.getHeight();

                    float scaleFactorW = (float) particleGridSide / sourceW;
                    float scaleFactorH = (float) particleGridSide / sourceH;
                    float scaleFactor = Math.min(scaleFactorW, scaleFactorH);
                    int scaledW = (int) (sourceW * scaleFactor);
                    int scaledH = (int) (sourceH * scaleFactor);

                    final int pixelCount = scaledW * scaledH;

                    BufferedImage scaledImage = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = scaledImage.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(sourceImage, 0, 0, scaledW, scaledH, null);
                    g.dispose();
                    final float centerImageX = (width - scaledW) / 2.0f;
                    final float centerImageY = (height - scaledH) / 2.0f;

                    int baseIndex = 0;
                    for (int y = 0; y < scaledH; y++) {
                        for (int x = 0; x < scaledW; x++) {
                            positionsX[baseIndex] = centerImageX + x + 0.5f;
                            positionsY[baseIndex] = centerImageY + y + 0.5f;
                            velocitiesX[baseIndex] = 0;
                            velocitiesY[baseIndex] = 0;
                            colors[baseIndex] = scaledImage.getRGB(x, y);
                            baseIndex++;
                        }
                    }

                    for (int i = baseIndex; i < N; i++) {
                        int idx = i % pixelCount;
                        positionsX[i] = positionsX[idx];
                        positionsY[i] = positionsY[idx];
                        // startX[i] = baseX[sourceIndex];
                        // startY[i] = baseY[sourceIndex];
                        velocitiesX[i] = 0;
                        velocitiesY[i] = 0;
                        colors[i] = colors[idx];
                    }

                } catch (Exception e) {
                    JOptionPane.showMessageDialog(
                            null,
                            "Error reading file: " + e.getMessage(),
                            "Loading Error",
                            JOptionPane.ERROR_MESSAGE);
                    e.printStackTrace();
                }
            }
        });
    }

    private void placeParticlesSquareMulti() {
        final float L_CONSTANT = 0.7f;
        final float C_CONSTANT = 0.25f;
        final float EPSILON = 1.0f;

        final int NUM_CENTERS = 5;
        final float[] targetX = new float[NUM_CENTERS];
        final float[] targetY = new float[NUM_CENTERS];

        final float margin = 0.12f;
        final float minX = this.width * margin;
        final float maxX = this.width * (1.0f - margin);
        final float rangeX = maxX - minX;

        final float minY = this.height * margin;
        final float maxY = this.height * (1.0f - margin);
        final float rangeY = maxY - minY;

        for (int j = 0; j < NUM_CENTERS; j++) {
            targetX[j] = minX + rangeX * fastRandomFloat();
            targetY[j] = minY + rangeY * fastRandomFloat();
        }

        for (int i = 0; i < NUM_PARTICLES; i++) {
            positionsX[i] = (float) this.width * fastRandomFloat();
            positionsY[i] = (float) this.height * fastRandomFloat();
            // startX[i] = positionsX[i];
            // startY[i] = positionsY[i];
            velocitiesX[i] = 0;
            velocitiesY[i] = 0;

            float totalWeight = 0;
            double blendedA = 0;
            double blendedB = 0;

            for (int j = 0; j < NUM_CENTERS; j++) {
                float dxToCenter = positionsX[i] - targetX[j];
                float dyToCenter = positionsY[i] - targetY[j];
                float distSq = dxToCenter * dxToCenter + dyToCenter * dyToCenter;
                float weight = 1.0f / (distSq + EPSILON);
                totalWeight += weight;
                double angleRadians = Math.atan2(dyToCenter, dxToCenter);
                double centerA = C_CONSTANT * Math.cos(angleRadians);
                double centerB = C_CONSTANT * Math.sin(angleRadians);

                blendedA += centerA * weight;
                blendedB += centerB * weight;
            }

            float finalA = (float) (blendedA / totalWeight);
            float finalB = (float) (blendedB / totalWeight);

            colors[i] = calculateOklabColor(L_CONSTANT, finalA, finalB);
        }
    }
}

class ParticleUpdateTask implements Runnable {
    private static final VectorSpecies<Float> F_SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> I_SPECIES = IntVector.SPECIES_PREFERRED;

    private static final int LANE_SIZE = F_SPECIES.length();

    private int startIndex;
    private int endIndex;
    private ParticlePanel panel;
    private float deltaTime;
    private int id;
    private int panDx;
    private int panDy;
    private float vScale;

    public ParticleUpdateTask() {
    }

    public void updateParams(int id, int start, int end, ParticlePanel panel, float deltaTime, int panX, int panY,
            float vScale) {
        this.startIndex = start;
        this.endIndex = end;
        this.panel = panel;
        this.deltaTime = deltaTime;
        this.id = id;
        this.panDx = panX;
        this.panDy = panY;
        this.vScale = vScale;
    }

    @Override
    public void run() {
        final float[] positionsX = panel.positionsX;
        final float[] positionsY = panel.positionsY;
        final float[] velocitiesX = panel.velocitiesX;
        final float[] velocitiesY = panel.velocitiesY;
        final int[] colors = panel.colors;
        final int w = panel.getWidth();
        final int h = panel.getHeight();

        // Constants derived from ParticlePanel state
        final FloatVector MOUSE_X_VEC = FloatVector.broadcast(F_SPECIES, (float) panel.mousePosition.x);
        final FloatVector MOUSE_Y_VEC = FloatVector.broadcast(F_SPECIES, (float) panel.mousePosition.y);
        final float minPullDist = panel.MIN_PULL_DIST;
        final float gf = panel.PULL_FORCE * deltaTime;
        final float frictionScalar = (float) Math.pow(panel.FRICTION, deltaTime);
        final FloatVector FRICTION_DT_VEC = FloatVector.broadcast(F_SPECIES, frictionScalar);
        final boolean mouseIsPressed = panel.isMousePressed;

        final int vectorEndIndex = startIndex + ((endIndex - startIndex) / LANE_SIZE) * LANE_SIZE;
        final float ox = this.panDx;
        final float oy = this.panDy;

        for (int i = startIndex; i < vectorEndIndex; i += LANE_SIZE) {
            FloatVector px = FloatVector.fromArray(F_SPECIES, positionsX, i);
            FloatVector py = FloatVector.fromArray(F_SPECIES, positionsY, i);
            FloatVector vx = FloatVector.fromArray(F_SPECIES, velocitiesX, i);
            FloatVector vy = FloatVector.fromArray(F_SPECIES, velocitiesY, i);

            if (mouseIsPressed) {
                FloatVector dx = MOUSE_X_VEC.sub(px);
                FloatVector dy = MOUSE_Y_VEC.sub(py);
                FloatVector distSq = dx.mul(dx).add(dy.mul(dy));
                var gravityMask = distSq.compare(GT, minPullDist);

                if (gravityMask.anyTrue()) {
                    FloatVector dist = distSq.sqrt();
                    FloatVector forceX = dx.div(dist).mul(gf);
                    FloatVector forceY = dy.div(dist).mul(gf);
                    vx = vx.add(forceX, gravityMask);
                    vy = vy.add(forceY, gravityMask);
                }
            }

            px = px.add(vx.mul(deltaTime)).add(ox);
            py = py.add(vy.mul(deltaTime)).add(oy);
            vx = vx.mul(FRICTION_DT_VEC);
            vy = vy.mul(FRICTION_DT_VEC);

            px.intoArray(positionsX, i);
            py.intoArray(positionsY, i);
            vx.intoArray(velocitiesX, i);
            vy.intoArray(velocitiesY, i);
        }

        for (int i = vectorEndIndex; i < endIndex; i++) {
            float px = positionsX[i];
            float py = positionsY[i];
            float vx = velocitiesX[i];
            float vy = velocitiesY[i];

            if (mouseIsPressed) {
                float dx = (float) MOUSE_X_VEC.lane(0) - px;
                float dy = (float) MOUSE_Y_VEC.lane(0) - py;
                float distSq = dx * dx + dy * dy;

                if (distSq > 1.0f) {
                    float dist = (float) Math.sqrt(distSq);
                    float forceX = (dx / dist) * gf;
                    float forceY = (dy / dist) * gf;
                    vx += forceX;
                    vy += forceY;
                }
            }

            px += vx * deltaTime + ox;
            py += vy * deltaTime + oy;
            vx *= frictionScalar;
            vy *= frictionScalar;

            positionsX[i] = px;
            positionsY[i] = py;
            velocitiesX[i] = vx;
            velocitiesY[i] = vy;
        }

        if (this.vScale != 0) {
            final float vs = vScale;
            for (int i = startIndex; i < endIndex; i++) {
                velocitiesX[i] *= vs;
                velocitiesY[i] *= vs;
            }
        }

        var pixels = panel.threadPixelBuffers[id];
        Arrays.fill(pixels, 0);
        for (int i = startIndex; i < endIndex; i++) {
            int px = (int) Math.min(Math.max(positionsX[i], 0), w - 1);
            int py = (int) Math.min(Math.max(positionsY[i], 0), h - 1);
            int index = py * w + px;
            pixels[index] = colors[i];
        }
    }
}