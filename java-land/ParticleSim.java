import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.Arrays;

import jdk.incubator.vector.FloatVector;
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

        int width = 1600;
        int height = 900;

        ParticlePanel particlePanel = new ParticlePanel(width, height);
        frame.add(particlePanel);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        particlePanel.startSimulation();
    }
}

class ParticlePanel extends JPanel implements ActionListener, MouseListener, MouseMotionListener {

    private static final VectorSpecies<Float> F_SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final int LANE_SIZE = F_SPECIES.length();
    private static final FloatVector PULL_VEC = FloatVector.broadcast(F_SPECIES, 500f);
    private static final FloatVector MIN_DIST_SQ_VEC = FloatVector.broadcast(F_SPECIES, 1.0f);

    private static final FloatVector BOUNCE_MULTIPLIER_VEC = FloatVector.broadcast(F_SPECIES, -1.0f);
    private static final float FRICTION = 0.7f;

    private static final int NUM_PARTICLES = 80_000_000;
    private static final int UPDATE_RATE = 1000 / 60;

    private float[] positionsX = new float[NUM_PARTICLES];
    private float[] positionsY = new float[NUM_PARTICLES];
    private float[] velocitiesX = new float[NUM_PARTICLES];
    private float[] velocitiesY = new float[NUM_PARTICLES];

    private final BufferedImage image;
    private final byte[] pixelArray;
    private final int panelWidth;
    private final int panelHeight;

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

        this.panelWidth = width;
        this.panelHeight = height;
        image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        pixelArray = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();

        initializeParticles();
        addMouseListener(this);
        addMouseMotionListener(this);
        timer = new Timer(UPDATE_RATE, this);
        setBackground(Color.BLACK);

        lastTickTime = System.nanoTime();
        fpsTimer = lastTickTime;
        frames = 0;
    }

    private void initializeParticles() {
        Random rand = new Random();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            positionsX[i] = (float) panelWidth * rand.nextFloat();
            positionsY[i] = (float) panelHeight * rand.nextFloat();
            velocitiesX[i] = (rand.nextFloat() - 0.5f) * 1.5f;
            velocitiesY[i] = (rand.nextFloat() - 0.5f) * 1.5f;
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

        updatePhysics(deltaTime);
        renderToPixelArray();
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

    // private void updatePhysics(float deltaTime) {
    // int w = this.panelWidth;
    // int h = this.panelHeight;
    // float wFloat = (float) w;
    // float hFloat = (float) h;

    // FloatVector DT_VEC = FloatVector.broadcast(F_SPECIES, deltaTime);
    // FloatVector W_VEC = FloatVector.broadcast(F_SPECIES, wFloat);
    // FloatVector H_VEC = FloatVector.broadcast(F_SPECIES, hFloat);
    // FloatVector ZERO_VEC = FloatVector.broadcast(F_SPECIES, 0.0f);
    // FloatVector MOUSE_X_VEC = FloatVector.broadcast(F_SPECIES, (float)
    // mousePosition.x);
    // FloatVector MOUSE_Y_VEC = FloatVector.broadcast(F_SPECIES, (float)
    // mousePosition.y);
    // FloatVector PULL_SCALED_VEC = PULL_VEC.mul(DT_VEC);
    // float frictionScalar = (float) Math.pow(FRICTION, deltaTime);
    // FloatVector FRICTION_DT_VEC = FloatVector.broadcast(F_SPECIES,
    // frictionScalar);

    // int i = 0;
    // for (; i <= NUM_PARTICLES - LANE_SIZE; i += LANE_SIZE) {

    // FloatVector px = FloatVector.fromArray(F_SPECIES, positionsX, i);
    // FloatVector py = FloatVector.fromArray(F_SPECIES, positionsY, i);
    // FloatVector vx = FloatVector.fromArray(F_SPECIES, velocitiesX, i);
    // FloatVector vy = FloatVector.fromArray(F_SPECIES, velocitiesY, i);

    // if (isMousePressed) {
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

    // float pullForce = PULL_VEC.lane(0) * deltaTime;
    // float dt_scalar = deltaTime;

    // for (; i < NUM_PARTICLES; i++) {
    // float px = positionsX[i];
    // float py = positionsY[i];
    // float vx = velocitiesX[i];
    // float vy = velocitiesY[i];

    // if (isMousePressed) {
    // float dx = (float) mousePosition.x - px;
    // float dy = (float) mousePosition.y - py;
    // float distSq = dx * dx + dy * dy;

    // if (distSq > 1.0f) {
    // float dist = (float) Math.sqrt(distSq);
    // float forceX = (dx / dist) * pullForce;
    // float forceY = (dy / dist) * pullForce;
    // vx += forceX;
    // vy += forceY;
    // }
    // }

    // vx *= frictionScalar;
    // vy *= frictionScalar;
    // px += vx * dt_scalar;
    // py += vy * dt_scalar;

    // if (px < 0) {
    // vx = -vx;
    // px = 0;
    // } else if (px > w) {
    // vx = -vx;
    // px = w;
    // }
    // if (py < 0) {
    // vy = -vy;
    // py = 0;
    // } else if (py > h) {
    // vy = -vy;
    // py = h;
    // }

    // positionsX[i] = px;
    // positionsY[i] = py;
    // velocitiesX[i] = vx;
    // velocitiesY[i] = vy;
    // }
    // }

    private void updatePhysics(float deltaTime) {
        final int w = this.panelWidth;
        final int h = this.panelHeight;
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

            var maskLeftX = px.compare(LT, ZERO_VEC);
            var maskRightX = px.compare(GT, W_VEC);
            var maskBounceX = maskLeftX.or(maskRightX);

            vx = vx.blend(vx.mul(BOUNCE_MULTIPLIER_VEC), maskBounceX);

            px = px.blend(ZERO_VEC, maskLeftX);
            px = px.blend(W_VEC, maskRightX);

            var maskTopY = py.compare(LT, ZERO_VEC);
            var maskBottomY = py.compare(GT, H_VEC);
            var maskBounceY = maskTopY.or(maskBottomY);

            vy = vy.blend(vy.mul(BOUNCE_MULTIPLIER_VEC), maskBounceY);

            py = py.blend(ZERO_VEC, maskTopY);
            py = py.blend(H_VEC, maskBottomY);

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

    private void renderToPixelArray() {
        final int w = panelWidth;
        final int h = panelHeight;
        final byte empty = 0;
        final int full = 255;

        Arrays.fill(pixelArray, empty);
        for (int i = 0; i < NUM_PARTICLES; i++) {
            int px = (int) positionsX[i];
            int py = (int) positionsY[i];
            int index = py * w + px;

            if (px < 0 || px >= w || py < 0 || py >= h) {
                continue;
            }
            // pixelArray[index] = full;

            int lu = pixelArray[index] & 0xFF;
            lu = Math.min(255, lu + 1);
            pixelArray[index] = (byte) lu;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(image, 0, 0, this);

        if (isMousePressed) {
            Graphics2D g2d = (Graphics2D) g;
            g2d.setColor(Color.WHITE);
            int size = 10;
            g2d.drawOval(mousePosition.x - size / 2, mousePosition.y - size / 2, size, size);
        }
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
}