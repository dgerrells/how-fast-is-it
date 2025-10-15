use std::sync::{Arc, Mutex, Condvar, mpsc};
use std::thread;
use std::thread::JoinHandle;
// I will be real, chatgpt helped me in this file.
// I don't like it and over time I am sure I will get better.
pub struct ThreadPool {
    workers: Vec<Worker>,
    sender: mpsc::Sender<Message>,
    job_count: Arc<(Mutex<usize>, Condvar)>,
}

type Job = Box<dyn FnOnce() + Send + 'static>;

enum Message {
    NewJob(Job),
}

impl ThreadPool {
    pub fn new(size: usize) -> ThreadPool {
        assert!(size > 0); // nifty

        let (sender, receiver) = mpsc::channel();
        let receiver = Arc::new(Mutex::new(receiver));

        let job_count = Arc::new((Mutex::new(0), Condvar::new()));
        
        let mut workers = Vec::with_capacity(size);
        for id in 0..size {
            workers.push(Worker::new(id, Arc::clone(&receiver), Arc::clone(&job_count)));
        }

        ThreadPool {
            workers,
            sender,
            job_count,
        }
    }

    pub fn execute<F>(&self, f: F)
    where
        F: FnOnce() + Send + 'static,
    {
        let (lock, _) = &*self.job_count;
        *lock.lock().unwrap() += 1;

        let job = Box::new(f);
        self.sender.send(Message::NewJob(job)).unwrap();
    }

    pub fn wait_for_completion(&self) {
        let (lock, cvar) = &*self.job_count;
        let mut count = lock.lock().unwrap();
        while *count > 0 {
            count = cvar.wait(count).unwrap();
        }
    }
}

pub struct Worker {
    id: usize,
    handle: Option<JoinHandle<()>>,
}

impl Worker {
    fn new(id: usize, receiver: Arc<Mutex<mpsc::Receiver<Message>>>, job_count: Arc<(Mutex<usize>, Condvar)>) -> Worker {
        let handle = thread::spawn(move || loop {
            let message = receiver.lock().unwrap().recv();
            match message {
                Ok(Message::NewJob(job)) => {
                    job();
                    let (lock, cvar) = &*job_count;
                    let mut count = lock.lock().unwrap();
                    *count -= 1;
                    cvar.notify_all();
                }
                Err(_) => break,
            }
        });

        Worker {
            id,
            handle: Some(handle),
        }
    }
}