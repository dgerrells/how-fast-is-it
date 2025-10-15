#![feature(portable_simd)]

mod thread_pool;

use minifb::{Key, MouseButton, Window, WindowOptions};
use rand::Rng;
use std::simd::{cmp::SimdPartialOrd, f32x8, simd_swizzle, StdFloat};
use std::time::Instant;
use thread_pool::ThreadPool;

const WIDTH: usize = 1200;
const HEIGHT: usize = 800;
const PARTICLE_COUNT: usize = 100_000_000;
const THREAD_COUNT: usize = 8;
const PARTICLE_STRIDE: usize = 2;
const GRAVITY_STRENGTH: f32 = 200.5 * 7.0;
const MAX_PERF_SAMPLE_FRAMES: usize = 10;

fn main() {
    let mut shared_pos_buffer = vec![0.0; PARTICLE_COUNT * PARTICLE_STRIDE].into_boxed_slice();
    let mut shared_vel_buffer = vec![0.0; PARTICLE_COUNT * PARTICLE_STRIDE].into_boxed_slice();
    let mut shared_p_count_buffer = vec![0 as u8; WIDTH * HEIGHT * THREAD_COUNT].into_boxed_slice();

    let mut rng = rand::thread_rng();
    let mut frame_buffer: Vec<u32> = vec![0; WIDTH * HEIGHT];
    let mut p_count_buffer = vec![0 as u8; WIDTH * HEIGHT];

    let mut window =
        Window::new("Safu", WIDTH, HEIGHT, WindowOptions::default()).unwrap_or_else(|e| {
            panic!("{}", e);
        });
    window.set_target_fps(120);

    let mut i: usize = 0;
    while i < PARTICLE_COUNT {
        let idx = i * PARTICLE_STRIDE;
        shared_pos_buffer[idx] = rng.gen_range(0.0..WIDTH as f32);
        shared_pos_buffer[idx + 1] = rng.gen_range(0.0..HEIGHT as f32);
        shared_vel_buffer[idx] = rng.gen_range(-2.0..2.0);
        shared_vel_buffer[idx + 1] = rng.gen_range(-2.0..2.0);
        i += 1;
    }

    let mut mouse_pos = (WIDTH as f32 / 2.0, HEIGHT as f32 / 2.0);
    let mut mouse_pressed = false;
    let friction_per_second = 0.99_f32.powf(60.0);

    let mut frame_count = 0;
    let mut last_fps_time = Instant::now();
    let mut last_frame_time = Instant::now();
    let mut simulation_times = vec![];
    let mut drawing_times = vec![];
    let mut fps = 0;

    let pool = ThreadPool::new(THREAD_COUNT);
    let chunk_size = PARTICLE_COUNT / THREAD_COUNT;
    let shared_pos_buff_ptr = shared_pos_buffer.as_mut_ptr();
    let shared_vel_buff_ptr = shared_vel_buffer.as_mut_ptr();
    let shared_p_count_buff_ptr = shared_p_count_buffer.as_mut_ptr();

    while window.is_open() && !window.is_key_down(Key::Escape) {
        let now = Instant::now();
        let delta_time = now.duration_since(last_frame_time).as_secs_f32();
        last_frame_time = now;

        let sim_start = Instant::now();

        if let Some((mx, my)) = window.get_mouse_pos(minifb::MouseMode::Clamp) {
            mouse_pos = (mx, my);
        }
        mouse_pressed = window.get_mouse_down(MouseButton::Left);

        // clear p_counts
        shared_p_count_buffer.iter_mut().for_each(|c| *c = 0);
        for t in 0..THREAD_COUNT {
            let mouse_pos = mouse_pos;

            let p_data_start = t * chunk_size * PARTICLE_STRIDE;
            let p_data_end = if t == THREAD_COUNT - 1 {
                PARTICLE_COUNT * PARTICLE_STRIDE
            } else {
                (t + 1) * chunk_size * PARTICLE_STRIDE
            };
            let p_count_start = t * WIDTH * HEIGHT;
            let p_count_end = if t == THREAD_COUNT - 1 {
                WIDTH * HEIGHT * THREAD_COUNT
            } else {
                (t + 1) * WIDTH * HEIGHT
            };

            unsafe {
                let pos_chunk = std::slice::from_raw_parts_mut(
                    shared_pos_buff_ptr.add(p_data_start),
                    p_data_end - p_data_start,
                );
                let vel_chunk = std::slice::from_raw_parts_mut(
                    shared_vel_buff_ptr.add(p_data_start),
                    p_data_end - p_data_start,
                );
                let p_count_chunk = std::slice::from_raw_parts_mut(
                    shared_p_count_buff_ptr.add(p_count_start),
                    p_count_end - p_count_start,
                );
                let friction = friction_per_second.powf(delta_time);
                pool.execute(move || {
                    let gravity_vec = f32x8::splat(GRAVITY_STRENGTH);
                    let delta_time_vec = f32x8::splat(delta_time);
                    let friction_vec = f32x8::splat(friction);
                    let mouse_pos_vec = f32x8::from_array([
                        mouse_pos.0,
                        mouse_pos.1,
                        mouse_pos.0,
                        mouse_pos.1,
                        mouse_pos.0,
                        mouse_pos.1,
                        mouse_pos.0,
                        mouse_pos.1,
                    ]);
                    let threshold_vec = f32x8::splat(2.0);

                    for (pos, vel) in pos_chunk
                        .chunks_exact_mut(8)
                        .zip(vel_chunk.chunks_exact_mut(8))
                    {
                        let mut pos_vec = f32x8::from_slice(pos);
                        let mut vel_vec = f32x8::from_slice(vel);

                        if mouse_pressed {
                            let delta = mouse_pos_vec - pos_vec;
                            let distance_sq = delta * delta;
                            let distance_vec: f32x8 =
                                (simd_swizzle!(distance_sq, [1, 0, 3, 2, 5, 4, 7, 6])
                                    + distance_sq)
                                    .sqrt();
                            let mask = distance_vec.simd_gt(threshold_vec);
                            let inv_gravity = gravity_vec / distance_vec;
                            let force = delta * inv_gravity * delta_time_vec;
                            vel_vec = mask.select(vel_vec + force, vel_vec);
                        }

                        vel_vec *= friction_vec;
                        pos_vec += vel_vec * delta_time_vec;

                        pos.copy_from_slice(pos_vec.as_array());
                        vel.copy_from_slice(vel_vec.as_array());

                        for j in (0..8).step_by(2) {
                            let x = pos[j] as usize;
                            let y = pos[j + 1] as usize;
                            let buffer_index =
                                y.max(0).min(HEIGHT - 1) * WIDTH + x.max(0).min(WIDTH - 1);
                            let p_count = p_count_chunk.get_unchecked(buffer_index);
                            p_count_chunk[buffer_index] = p_count.saturating_add(1);
                        }
                    }
                });
            }
        }

        pool.wait_for_completion();

        simulation_times.push(sim_start.elapsed().as_secs_f32());
        if simulation_times.len() > MAX_PERF_SAMPLE_FRAMES {
            simulation_times.remove(0);
        }

        let draw_start = Instant::now();
        p_count_buffer.iter_mut().for_each(|c| *c = 0);
        for counts in shared_p_count_buffer.chunks_mut(WIDTH * HEIGHT) {
            for (i, &count) in counts.iter().enumerate() {
                p_count_buffer[i] = p_count_buffer[i].saturating_add(count);
            }
        }

        frame_buffer.iter_mut().for_each(|pixel| *pixel = 0);
        for (i, &count) in p_count_buffer.iter().enumerate() {
            let x = i % WIDTH;
            let y = i / WIDTH;
            let r = (x as f32 / WIDTH as f32 * 10.0 * count.clamp(0, 30) as f32) as u8;
            let g = (y as f32 / HEIGHT as f32 * 10.0 * count.clamp(0, 30) as f32) as u8;
            let b = (10.0 * count.clamp(0, 30) as f32) as u8;

            frame_buffer[i] = ((r as u32) << 16) | ((g as u32) << 8) | (b as u32);
        }

        window
            .update_with_buffer(&frame_buffer, WIDTH, HEIGHT)
            .unwrap();

        drawing_times.push(draw_start.elapsed().as_secs_f32());
        if drawing_times.len() > MAX_PERF_SAMPLE_FRAMES {
            drawing_times.remove(0);
        }

        frame_count += 1;
        if now.duration_since(last_fps_time).as_secs_f32() >= 1.0 {
            fps = frame_count;
            frame_count = 0;
            last_fps_time = now;

            let avg_simulation_time: f32 =
                simulation_times.iter().sum::<f32>() / simulation_times.len() as f32;
            let avg_drawing_time: f32 =
                drawing_times.iter().sum::<f32>() / drawing_times.len() as f32;

            println!("FPS: {}", fps);
            println!("Avg Simulation Time: {:.5}", avg_simulation_time * 1000.0);
            println!("Avg Drawing Time: {:.5}", avg_drawing_time * 1000.0);
        }
    }
}
