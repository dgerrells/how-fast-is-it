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

        int width = 600;
        int height = 400;

        ParticlePanel particlePanel = new ParticlePanel(width, height);
        frame.add(particlePanel);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        particlePanel.startSimulation();
    }
}

class ParticlePanel extends JPanel implements ActionListener, MouseListener, MouseMotionListener, ComponentListener {

    private static final VectorSpecies<Float> F_SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final int LANE_SIZE = F_SPECIES.length();
    private static final FloatVector PULL_VEC = FloatVector.broadcast(F_SPECIES, 500f);
    private static final FloatVector MIN_DIST_SQ_VEC = FloatVector.broadcast(F_SPECIES, 1.0f);

    private static final FloatVector BOUNCE_MULTIPLIER_VEC = FloatVector.broadcast(F_SPECIES, -1.0f);
    private static final float FRICTION = 0.7f;

    private static final int NUM_PARTICLES = 10_000_000;
    private static final int UPDATE_RATE = 1000 / 60;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private float[] positionsX = new float[NUM_PARTICLES];
    private float[] positionsY = new float[NUM_PARTICLES];
    private float[] velocitiesX = new float[NUM_PARTICLES];
    private float[] velocitiesY = new float[NUM_PARTICLES];

    private BufferedImage image;
    private byte[] pixelArray;
    private int width;
    private int height;
    private byte[][] threadPixelBuffers;

    private Point mousePosition = new Point(0, 0);
    private boolean isMousePressed = false;
    private final Timer timer;

    private long lastTickTime;
    private long fpsTimer;
    private int frames;
    private static final long ONE_SECOND_NS = 1_000_000_000L;

    private boolean isTicking = false;

    public ParticlePanel(int width, int height) {
        setPreferredSize(new Dimension(width, height));
        this.handleResize(width, height);

        initializeParticles();
        addMouseListener(this);
        addMouseMotionListener(this);
        addComponentListener(this);
        timer = new Timer(UPDATE_RATE, this);
        setBackground(Color.BLACK);

        lastTickTime = System.nanoTime();
        fpsTimer = lastTickTime;
        frames = 0;
    }

    private void initializeParticles() {
        Random rand = new Random();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            positionsX[i] = (float) this.width * rand.nextFloat();
            positionsY[i] = (float) this.height * rand.nextFloat();
            velocitiesX[i] = 0;
            velocitiesY[i] = 0;
        }
    }

    public void startSimulation() {
        timer.start();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (isTicking) {
            return;
        }
        isTicking = true;

        long now = System.nanoTime();

        float deltaTime = (now - lastTickTime) / 1_000_000_000.0f;
        lastTickTime = now;

        tick(deltaTime);
        render();
        repaint();

        frames++;
        if (now - fpsTimer >= ONE_SECOND_NS) {
            float fps = (float) frames * ONE_SECOND_NS / (now - fpsTimer);
            System.out.printf("FPS: %.2f\n", fps);
            System.out.println("panel size: " + getWidth() + " " + getHeight());
            frames = 0;
            fpsTimer = now;
        }
        isTicking = false;
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

        // maybe better cache locality
        // final int LANE_SIZE = F_SPECIES.vectorShape().vectorBitSize() / Float.SIZE;
        // final int TOTAL_VECTORIZED_PARTICLES = SCALAR_START_INDEX;

        // IntStream.range(0, CPU_COUNT).parallel().forEach(threadId -> {
        // int particlesPerThread = TOTAL_VECTORIZED_PARTICLES / CPU_COUNT;
        // int startIndex = threadId * particlesPerThread;
        // int endIndex = startIndex + particlesPerThread;

        // if (threadId == CPU_COUNT - 1) {
        // endIndex = TOTAL_VECTORIZED_PARTICLES;
        // }

        // startIndex = (startIndex / LANE_SIZE) * LANE_SIZE;
        // endIndex = (endIndex / LANE_SIZE) * LANE_SIZE;

        // for (int i = startIndex; i < endIndex; i += LANE_SIZE) {

        // FloatVector px = FloatVector.fromArray(F_SPECIES, positionsX, i);
        // FloatVector py = FloatVector.fromArray(F_SPECIES, positionsY, i);
        // FloatVector vx = FloatVector.fromArray(F_SPECIES, velocitiesX, i);
        // FloatVector vy = FloatVector.fromArray(F_SPECIES, velocitiesY, i);

        // if (mouseIsPressed) {
        // FloatVector dx = MOUSE_X_VEC.sub(px);
        // FloatVector dy = MOUSE_Y_VEC.sub(py);
        // FloatVector distSq = dx.mul(dx).add(dy.mul(dy));
        // var gravityMask = distSq.compare(GT, MIN_DIST_SQ_VEC);

        // if (gravityMask.anyTrue()) {
        // FloatVector dist = distSq.sqrt();
        // FloatVector forceX = dx.div(dist).mul(PULL_SCALED_VEC);
        // FloatVector forceY = dy.div(dist).mul(PULL_SCALED_VEC);
        // vx = vx.add(forceX, gravityMask);
        // vy = vy.add(forceY, gravityMask);
        // }
        // }

        // vx = vx.mul(FRICTION_DT_VEC);
        // vy = vy.mul(FRICTION_DT_VEC);
        // px = px.add(vx.mul(DT_VEC));
        // py = py.add(vy.mul(DT_VEC));

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

        // px.intoArray(positionsX, i);
        // py.intoArray(positionsY, i);
        // vx.intoArray(velocitiesX, i);
        // vy.intoArray(velocitiesY, i);
        // }
        // });

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
        Arrays.fill(pixelArray, empty);

        // for (int i = 0; i < NUM_PARTICLES; i++) {
        // int px = (int) positionsX[i];
        // int py = (int) positionsY[i];
        // int index = py * w + px;

        // if (px < 0 || px >= w || py < 0 || py >= h) {
        // continue;
        // }

        // int lu = pixelArray[index] & 0xFF;
        // lu = Math.min(255, lu + 20);
        // pixelArray[index] = (byte) lu;
        // }

        final int PARTICLES_PER_CHUNK = NUM_PARTICLES / CPU_COUNT;
        IntStream.range(0, CPU_COUNT).parallel().forEach(chunkIndex -> {
            byte[] localPixelArray = threadPixelBuffers[chunkIndex];
            Arrays.fill(localPixelArray, empty);
            int start = chunkIndex * PARTICLES_PER_CHUNK;
            int end = (chunkIndex == CPU_COUNT - 1) ? NUM_PARTICLES
                    : start +
                            PARTICLES_PER_CHUNK;

            for (int i = start; i < end; i++) {
                int px = (int) positionsX[i];
                int py = (int) positionsY[i];
                int index = py * w + px;

                if (px >= 0 && px < w && py >= 0 && py < h) {
                    // int lu = localPixelArray[index] & 0xFF;
                    // lu = Math.min(255, lu + 20);
                    // localPixelArray[index] = (byte) lu;

                    pixelArray[index] = (byte) 255;
                }
            }
        });

        // final int PIXEL_COUNT = pixelArray.length;
        // IntStream.range(0, CPU_COUNT).parallel().forEach(chunkIndex -> {
        // int chunkSize = PIXEL_COUNT / CPU_COUNT;
        // int start = chunkIndex * chunkSize;
        // int end = (chunkIndex == CPU_COUNT - 1) ? PIXEL_COUNT : start + chunkSize;

        // for (int localIndex = 0; localIndex < CPU_COUNT; localIndex++) {
        // byte[] localArray = threadPixelBuffers[localIndex];

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
        // int current = pixelArray[i] & 0xFF;
        // int local = localArray[i] & 0xFF;
        // int summed = current + local;
        // pixelArray[i] = (byte) Math.min(255, summed);
        // }
        // }
        // });

        int[] buff = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        final float BASE_SCALING = 2.0f;
        for (int i = 0; i < pixelArray.length; i++) {
            int count = pixelArray[i] & 0xFF;
            int y = i / w;
            int x = i % w;
            int col = 0;
            float r_base = (((float) y / h) * BASE_SCALING);
            float g_base = (((float) x / w) * BASE_SCALING);
            float b_base = ((1.0f - ((float) x / w)) * BASE_SCALING);
            int r_final = (int) Math.min(255.0f, r_base * count);
            int g_final = (int) Math.min(255.0f, g_base * count);
            int b_final = (int) Math.min(255.0f, b_base * count);
            col |= 0xFF << 24;
            col |= (r_final & 0xFF) << 16;
            col |= (g_final & 0xFF) << 8;
            col |= (b_final & 0xFF);

            buff[i] = col;
            // col |= 0xFF << 24;
            // int r_value = (int) (((float) y / h) * 255.0f * (count / 255.0f));
            // col |= (r_value & 0xFF) << 16;
            // int g_value = (int) (((float) x / w) * 255.0f * (count / 255.0f));
            // col |= (g_value & 0xFF) << 8;
            // int b_value = (int) ((1.0f - ((float) x / w)) * 255.0f * (count / 255.0f));
            // col |= (b_value & 0xFF);
            // buff[i] = col;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(image, 0, 0, this);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        isMousePressed = true;
        mousePosition = e.getPoint();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        isMousePressed = false;
    }

    @Override
    public void mouseDragged(MouseEvent e) {
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
        this.handleResize(getWidth(), getHeight());
    }

    private void handleResize(int w, int h) {
        this.width = w;
        this.height = h;
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        this.pixelArray = new byte[w * h];
        threadPixelBuffers = new byte[CPU_COUNT][];
        for (int i = 0; i < CPU_COUNT; i++) {
            threadPixelBuffers[i] = new byte[w * h];
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
}