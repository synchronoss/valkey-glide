// Copyright Valkey GLIDE Project Contributors - SPDX Identifier: Apache-2.0

#[cfg(feature = "proto")]
fn build_protobuf() {
    let customization_options = protobuf_codegen::Customize::default()
        .lite_runtime(false)
        .tokio_bytes(true)
        .tokio_bytes_for_string(true);
    let mut codegen = protobuf_codegen::Codegen::new();
    if let Ok(proto_path) = std::env::var("PROTOC_PATH") {
        codegen.protoc_path(std::path::Path::new(&proto_path));
    }
    codegen
        .cargo_out_dir("protobuf")
        .include("src")
        .input("src/protobuf/command_request.proto")
        .input("src/protobuf/response.proto")
        .input("src/protobuf/connection_request.proto")
        .customize(customization_options)
        .out_dir("src/generated")
        .run_from_script();
}

fn main() {
    #[cfg(feature = "proto")]
    build_protobuf();
}
