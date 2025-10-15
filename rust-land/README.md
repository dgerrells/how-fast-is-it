# Start

In the land of rust a particle sim was forced with a crude attempt at using a simd intriscs library. It is able to simulate over 100m particles at a somewhat functional speed. 

# Build

It is good to note to make sure to use a release build.

```sh
cargo install
cargo build --release
cargo run --release
```


# Wasm special edition

There is a wasm build which runs a basic slime mold simulation to give a little flavor for the web. 