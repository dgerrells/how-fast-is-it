import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.Arrays;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import static jdk.incubator.vector.VectorOperators.*;

/**
 * A 2D particle simulation using Java Swing, designed for performance
 * with a structure fully optimized for the Java Vector API (SIMD) usage.
 * Rendering uses a fast pixel array buffer with 8-bit particle "color" data.
 */
public class ParticleSim {

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {
            new ParticleSim().createAndShowGUI();
        });
    }

    private void createAndShowGUI() {
        JFrame frame = new JFrame("Vector API Particle Sim (Requires flags)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        int width = 900;
        int height = 680;

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

    private static final VectorSpecies<Float> F_SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final int LANE_SIZE = F_SPECIES.length();
    private static final FloatVector PULL_VEC = FloatVector.broadcast(F_SPECIES, 280f);
    private static final FloatVector MIN_DIST_SQ_VEC = FloatVector.broadcast(F_SPECIES, 1.0f);
    private static final float FRICTION = 0.9f;

    private static final int NUM_PARTICLES = 20_000_000;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private float[] positionsX = new float[NUM_PARTICLES];
    private float[] positionsY = new float[NUM_PARTICLES];
    private float[] velocitiesX = new float[NUM_PARTICLES];
    private float[] velocitiesY = new float[NUM_PARTICLES];
    private float[] startX = new float[NUM_PARTICLES];
    private float[] startY = new float[NUM_PARTICLES];
    private int[] colors = new int[NUM_PARTICLES];

    private BufferedImage image;
    // private byte[] pixelArray;
    private int width;
    private int height;
    // private byte[][] threadPixelBuffers;
    private int[][] threadPixelBuffers;

    private Point mousePosition = new Point(0, 0);
    private boolean isMousePressed = false;

    private volatile boolean running = false;
    private Thread gameLoopThread;
    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final double TARGET_FPS = 60.0;
    private static final double NS_PER_TICK = NS_PER_SECOND / TARGET_FPS;
    private boolean isResizeRequested = false;
    private boolean isResetRequested = false;
    private boolean isPanning = false;

    private long lastTickTime;
    private int frames = 0;

    public ParticlePanel(int width, int height) {
        setSize(width, height);
        setPreferredSize(new Dimension(width, height));
        this.handleResize(width, height);

        initializeParticles();
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

    private void initializeParticles() {
        final float centerX = this.width / 2.0f;
        final float centerY = this.height / 2.0f;

        final float L_CONSTANT = 0.7f;
        final float C_CONSTANT = 0.25f;

        for (int i = 0; i < NUM_PARTICLES; i++) {
            positionsX[i] = (float) this.width * fastRandomFloat();
            positionsY[i] = (float) this.height * fastRandomFloat();
            startX[i] = positionsX[i];
            startY[i] = positionsY[i];
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

                long tickStart = System.nanoTime();
                tick(deltaTime);
                long tickEnd = System.nanoTime();
                long tickDuration = (tickEnd - tickStart);

                long renderStart = System.nanoTime();
                render();
                long renderEnd = System.nanoTime();
                long renderDuration = (renderEnd - renderStart);

                Graphics g = getGraphics();
                g.drawImage(image, 0, 0, this);
                g.dispose();
                Toolkit.getDefaultToolkit().sync();
                frames++;

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
        final float wFloat = (float) w;
        final float hFloat = (float) h;

        final FloatVector DT_VEC = FloatVector.broadcast(F_SPECIES, deltaTime);
        final FloatVector W_VEC = FloatVector.broadcast(F_SPECIES, wFloat);
        final FloatVector H_VEC = FloatVector.broadcast(F_SPECIES, hFloat);
        final FloatVector ZERO_VEC = FloatVector.broadcast(F_SPECIES, 0.0f);
        final FloatVector MOUSE_X_VEC = FloatVector.broadcast(F_SPECIES, (float) mousePosition.x);
        final FloatVector MOUSE_Y_VEC = FloatVector.broadcast(F_SPECIES, (float) mousePosition.y);
        final FloatVector PULL_SCALED_VEC = PULL_VEC.mul(DT_VEC);
        final float frictionScalar = (float) Math.pow(FRICTION, deltaTime);
        final FloatVector FRICTION_DT_VEC = FloatVector.broadcast(F_SPECIES, frictionScalar);
        final float pullForce = PULL_VEC.lane(0) * deltaTime;
        final float dt_scalar = deltaTime;
        final boolean mouseIsPressed = isMousePressed;

        final int VECTOR_CHUNKS = NUM_PARTICLES / LANE_SIZE;
        final int SCALAR_START_INDEX = VECTOR_CHUNKS * LANE_SIZE;

        IntStream.range(0, VECTOR_CHUNKS).parallel().forEach(chunkIndex -> {
            int i = chunkIndex * LANE_SIZE;
            FloatVector px = FloatVector.fromArray(F_SPECIES, positionsX, i);
            FloatVector py = FloatVector.fromArray(F_SPECIES, positionsY, i);
            FloatVector vx = FloatVector.fromArray(F_SPECIES, velocitiesX, i);
            FloatVector vy = FloatVector.fromArray(F_SPECIES, velocitiesY, i);

            if (mouseIsPressed) {
                FloatVector dx = MOUSE_X_VEC.sub(px);
                FloatVector dy = MOUSE_Y_VEC.sub(py);
                FloatVector distSq = dx.mul(dx).add(dy.mul(dy));
                var gravityMask = distSq.compare(GT, MIN_DIST_SQ_VEC);

                if (gravityMask.anyTrue()) {
                    FloatVector dist = distSq.sqrt();
                    FloatVector forceX = dx.div(dist).mul(PULL_SCALED_VEC);
                    FloatVector forceY = dy.div(dist).mul(PULL_SCALED_VEC);
                    vx = vx.add(forceX, gravityMask);
                    vy = vy.add(forceY, gravityMask);
                }
            }

            vx = vx.mul(FRICTION_DT_VEC);
            vy = vy.mul(FRICTION_DT_VEC);
            px = px.add(vx.mul(DT_VEC));
            py = py.add(vy.mul(DT_VEC));

            // var maskLeftX = px.compare(LT, ZERO_VEC);
            // var maskRightX = px.compare(GT, W_VEC);
            // var maskBounceX = maskLeftX.or(maskRightX);

            // vx = vx.blend(vx.mul(BOUNCE_MULTIPLIER_VEC), maskBounceX);

            // px = px.blend(ZERO_VEC, maskLeftX);
            // px = px.blend(W_VEC, maskRightX);

            // var maskTopY = py.compare(LT, ZERO_VEC);
            // var maskBottomY = py.compare(GT, H_VEC);
            // var maskBounceY = maskTopY.or(maskBottomY);

            // vy = vy.blend(vy.mul(BOUNCE_MULTIPLIER_VEC), maskBounceY);

            // py = py.blend(ZERO_VEC, maskTopY);
            // py = py.blend(H_VEC, maskBottomY);

            px.intoArray(positionsX, i);
            py.intoArray(positionsY, i);
            vx.intoArray(velocitiesX, i);
            vy.intoArray(velocitiesY, i);
        });

        for (int i = SCALAR_START_INDEX; i < NUM_PARTICLES; i++) {
            float px = positionsX[i];
            float py = positionsY[i];
            float vx = velocitiesX[i];
            float vy = velocitiesY[i];

            if (mouseIsPressed) {
                float dx = (float) mousePosition.x - px;
                float dy = (float) mousePosition.y - py;
                float distSq = dx * dx + dy * dy;

                if (distSq > 1.0f) {
                    float dist = (float) Math.sqrt(distSq);
                    float forceX = (dx / dist) * pullForce;
                    float forceY = (dy / dist) * pullForce;
                    vx += forceX;
                    vy += forceY;
                }
            }

            vx *= frictionScalar;
            vy *= frictionScalar;
            px += vx * dt_scalar;
            py += vy * dt_scalar;

            if (px < 0) {
                vx = -vx;
                px = 0;
            } else if (px > w) {
                vx = -vx;
                px = w;
            }
            if (py < 0) {
                vy = -vy;
                py = 0;
            } else if (py > h) {
                vy = -vy;
                py = h;
            }

            positionsX[i] = px;
            positionsY[i] = py;
            velocitiesX[i] = vx;
            velocitiesY[i] = vy;
        }
    }

    private void render() {
        final int w = this.width;
        final int h = this.height;
        final byte empty = (byte) 0;
        final int full = 255;

        final int PARTICLES_PER_CHUNK = NUM_PARTICLES / CPU_COUNT;
        int[] buff = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        Arrays.fill(buff, 0);
        IntStream.range(0, CPU_COUNT).parallel().forEach(chunkIndex -> {
            int[] localPixelArray = threadPixelBuffers[chunkIndex];
            int max = localPixelArray.length - 1;
            Arrays.fill(localPixelArray, empty);
            int start = chunkIndex * PARTICLES_PER_CHUNK;
            int end = (chunkIndex == CPU_COUNT - 1) ? NUM_PARTICLES
                    : start +
                            PARTICLES_PER_CHUNK;

            for (int i = start; i < end; i++) {
                int px = (int) Math.min(Math.max(positionsX[i], 0), w - 1);
                int py = (int) Math.min(Math.max(positionsY[i], 0), h - 1);
                int index = py * w + px;
                localPixelArray[index] = colors[i];

                // if (px >= 0 && px < w && py >= 0 && py < h) {
                // int lu = localPixelArray[index] & 0xFF;
                // lu = Math.min(255, lu + 20);
                // localPixelArray[index] = (byte) lu;
                // buff[index] = colors[i];
                // localPixelArray[Math.max(0, Math.min(max, index))] = colors[i];
                // }
            }
        });

        final int PIXEL_COUNT = buff.length;
        IntStream.range(0, CPU_COUNT).parallel().forEach(chunkIndex -> {
            int chunkSize = PIXEL_COUNT / CPU_COUNT;
            int start = chunkIndex * chunkSize;
            int end = (chunkIndex == CPU_COUNT - 1) ? PIXEL_COUNT : start + chunkSize;

            for (int i = start; i < end; i++) {
                int a = 255;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int localIndex = 0; localIndex < CPU_COUNT; localIndex++) {
                    int col = threadPixelBuffers[localIndex][i];
                    if (col != 0) {
                        r = ((col >> 16) & 0xFF);
                        g = ((col >> 8) & 0xFF);
                        b = ((col >> 0) & 0xFF);
                        break;
                    }
                    // if (col == 0) {
                    // col = threadPixelBuffers[localIndex][i];
                    // }

                }
                buff[i] = Math.min(255, a) << 24 | Math.min(r, 255) << 16 | Math.min(g, 255) << 8
                        | Math.min(b, 255);
            }

            // for (int localIndex = 0; localIndex < CPU_COUNT; localIndex++) {
            // int[] localArray = threadPixelBuffers[localIndex];

            // int i = start;
            // // vector cannot handle unsigned bytes fing lame
            // // it also isn't faster so doesn't matter.
            // // for (; i <= end - W_LANE_SIZE; i += W_LANE_SIZE) {
            // // ByteVector mainPixels = ByteVector.fromArray(B_SPECIES, pixelArray, i);
            // // ByteVector localPixels = ByteVector.fromArray(B_SPECIES, localArray, i);
            // // ByteVector summedPixels = mainPixels.add(localPixels);
            // // ByteVector finalPixels = summedPixels.min(MAX_LUM).reinterpretAsBytes();
            // // finalPixels.intoArray(pixelArray, i);
            // // }

            // for (; i < end; i++) {
            // // int current = pixelArray[i] & 0xFF;
            // // int local = localArray[i] & 0xFF;
            // // int summed = current + local;
            // // pixelArray[i] = (byte) Math.min(255, summed);

            // int col = buff[i];
            // int a = (col << 24) & 0xFF;
            // int r = (col << 16) & 0xFF;
            // int g = (col << 8) & 0xFF;
            // int b = col & 0xFF;
            // int in = [i];
            // int a = (col << 24) & 0xFF;
            // int r = (col << 16) & 0xFF;
            // int g = (col << 8) & 0xFF;
            // int b = col & 0xFF;
            // buff[i] = localArray[i];
            // }
            // }
        });
    }

    private void processInputRequests() {
        if (this.isResizeRequested) {
            this.isResizeRequested = false;
            this.handleResize(getWidth(), getHeight());
        }
        if (this.isResetRequested) {
            this.isResetRequested = false;
            this.initializeParticles();
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
        Point last = mousePosition;
        if (isPanning) {
            last.x -= e.getPoint().x;
            last.y -= e.getPoint().y;
            for (int i = 0; i < NUM_PARTICLES; i++) {
                positionsX[i] -= last.x;
                positionsY[i] -= last.y;
            }
        }

        mousePosition = e.getPoint();
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
        System.out.println("w " + getWidth() + " h " + getHeight());
        this.isResizeRequested = true;
    }

    private void handleResize(int w, int h) {
        this.width = w;
        this.height = h;
        this.setSize(w, h);
        System.out.println("w2 " + getWidth() + " h2 " + getHeight());
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
            for (int i = 0; i < NUM_PARTICLES; i++) {
                velocitiesX[i] *= 0.2f;
                velocitiesY[i] *= 0.2f;
            }
        }
        if (e.getKeyChar() == 'r') {
            this.isResetRequested = true;
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }
}
