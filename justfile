alias bk := build_camd
alias bm := build_manager

build_camd:
    cross build --target aarch64-linux-android --release

build_manager: build_camd
    cp target/aarch64-linux-android/release/camd manager/app/src/main/jniLibs/arm64-v8a/libcamd.so
    cd manager && ./gradlew aDebug

clippy:
    cargo fmt
    cross clippy --target aarch64-linux-android --release
