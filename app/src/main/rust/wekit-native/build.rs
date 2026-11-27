use sha2::{Digest, Sha256};
use std::{
    env, fs,
    path::{Path, PathBuf},
    process::Command,
};

const LIBVPX_VERSION: &str = "1.16.0";
const LIBVPX_ARCHIVE_SHA256: &str =
    "7a479a3c66b9f5d5542a4c6a1b7d3768a983b1e5c14c60a9396edc9b649e015c";

fn main() {
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        println!("cargo:rustc-link-lib=log");
        println!("cargo:rustc-link-lib=dl");
        println!("cargo:rustc-link-lib=unwind");
    }

    build_vpx_decoder(&target_os);
    build_shadowhook(&target_os);

    let bindings = bindgen::Builder::default()
        .header("include/native_hook.h")
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
        .generate()
        .expect("Failed to generate bindings");

    let out_path = PathBuf::from(env::var("OUT_DIR").unwrap());
    bindings
        .write_to_file(out_path.join("bindings.rs"))
        .expect("Failed to write bindings");
}

fn build_vpx_decoder(target_os: &str) {
    println!("cargo:rerun-if-changed=include/wekit_vpx_decoder.c");
    let include_paths = if target_os == "android" {
        vec![build_android_libvpx()]
    } else {
        pkg_config::Config::new()
            .cargo_metadata(false)
            .probe("vpx")
            .expect("libvpx development files are required for host builds")
            .include_paths
    };

    let mut wrapper = cc::Build::new();
    wrapper.file("include/wekit_vpx_decoder.c");
    include_paths.iter().for_each(|path| {
        wrapper.include(path);
    });
    wrapper.compile("wekit_vpx_decoder");

    if target_os == "android" {
        println!("cargo:rustc-link-lib=static=vpx");
        println!("cargo:rustc-link-lib=m");
    } else {
        println!("cargo:rustc-link-lib=vpx");
    }
}

/// 把 ShadowHook（ByteDance android-inline-hook）静态编进本 .so。
///
/// LSPosed 的 `hook_func` 底层是 lsplant，只能改 ART 方法，对 libc 符号无效；
/// ShadowHook 直接改写目标函数入口，能覆盖 `openat`/`ptrace`/`syscall` 等。
fn build_shadowhook(target_os: &str) {
    if target_os != "android" {
        return;
    }

    let arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let arch_dir = match arch.as_str() {
        "aarch64" => "arm64",
        "arm" => "arm",
        other => panic!("unsupported Android arch for shadowhook: {other}"),
    };

    let root = Path::new("third_party/shadowhook");
    let mut build = cc::Build::new();
    build
        .flag("-std=c11")
        .flag("-Os")
        .flag("-ffunction-sections")
        .flag("-fdata-sections")
        .define("SHADOWHOOK_VERSION", "\"2.0.0\"")
        .include(root)
        .include(root.join("include"))
        .include(root.join("arch").join(arch_dir))
        .include(root.join("common"))
        .include(root.join("third_party/xdl"))
        .include(root.join("third_party/bsd"))
        .include(root.join("third_party/lss"))
        .warnings(false);

    // 上游 CMake 对 32 位 ARM 固定 ANDROID_ARM_MODE=arm：sh_glue.S 是 ARM 指令，
    // 与默认的 Thumb 模式不一致。
    if arch == "arm" {
        build.flag("-marm");
    }

    add_shadowhook_sources(&mut build, root);
    add_shadowhook_sources(&mut build, &root.join("arch").join(arch_dir));
    add_shadowhook_sources(&mut build, &root.join("common"));
    add_shadowhook_sources(&mut build, &root.join("third_party/xdl"));

    println!("cargo:rerun-if-changed=third_party/shadowhook");
    build.compile("shadowhook");
}

fn add_shadowhook_sources(build: &mut cc::Build, dir: &Path) {
    for entry in fs::read_dir(dir).expect("failed to read shadowhook source directory") {
        let path = entry.expect("failed to read shadowhook source entry").path();
        if matches!(
            path.extension().and_then(|e| e.to_str()),
            Some("c") | Some("S")
        ) {
            build.file(path);
        }
    }
}

fn build_android_libvpx() -> PathBuf {
    let out_dir = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR is missing"));
    let archive = out_dir.join(format!("libvpx-{LIBVPX_VERSION}.tar.gz"));
    let source = out_dir.join(format!("libvpx-{LIBVPX_VERSION}"));
    let build = out_dir.join("libvpx-build");
    let library = build.join("libvpx.a");

    if !source.join("configure").is_file() {
        download_libvpx(&archive);
        let status = Command::new("tar")
            .args(["-xzf"])
            .arg(&archive)
            .arg("-C")
            .arg(&out_dir)
            .status()
            .expect("failed to run tar while extracting libvpx");
        assert!(status.success(), "failed to extract libvpx");
    }

    if !library.is_file() {
        let _ = fs::remove_dir_all(&build);
        fs::create_dir_all(&build).expect("failed to create libvpx build directory");
        configure_android_libvpx(&source, &build);
        let jobs = std::thread::available_parallelism()
            .map(usize::from)
            .unwrap_or(2)
            .min(8);
        let status = Command::new("make")
            .arg(format!("-j{jobs}"))
            // libvpx 的 asm 规则是 `$(AS) $(ASFLAGS) -o $@ $<`，面向真正的 GNU as
            // （汇编器天然只产出 .o）。这里 AS 是 clang 驱动，缺 `-c` 时会把 .S
            // 当成要链接的可执行文件，报 `undefined symbol: main`。命令行变量
            // 优先级最高，且不影响 libs-*.mk 里的 ASFLAGS（-march/-mfpu/-I 等）。
            .arg(format!(
                "AS={} -c",
                target_tool("CC", &env::var("TARGET").expect("TARGET is missing")).display()
            ))
            .current_dir(&build)
            .status()
            .expect("failed to run make while building libvpx");
        assert!(
            status.success() && library.is_file(),
            "failed to build libvpx"
        );
    }

    println!("cargo:rustc-link-search=native={}", build.display());
    source
}

fn download_libvpx(archive: &Path) {
    if archive.is_file() && file_sha256(archive) == LIBVPX_ARCHIVE_SHA256 {
        return;
    }
    let url =
        format!("https://github.com/webmproject/libvpx/archive/refs/tags/v{LIBVPX_VERSION}.tar.gz");
    // GitHub 的源码包端点（codeload）偶发 503/504，无重试时一次抖动就挂掉整个构建；
    // curl 层先自带重试，外层再按「下载成功且校验通过」整体重试 3 轮。
    let mut last_error = String::from("no attempt made");
    for attempt in 1..=3 {
        let _ = fs::remove_file(archive);
        let status = Command::new("curl")
            .args([
                "--fail",
                "--location",
                "--silent",
                "--show-error",
                "--retry",
                "10",
                "--retry-all-errors",
                "--retry-delay",
                "5",
                "--connect-timeout",
                "20",
            ])
            .arg(&url)
            .arg("--output")
            .arg(archive)
            .status()
            .expect("failed to run curl while downloading libvpx");
        let curl_ok = status.success();
        let checksum_ok = archive.is_file() && file_sha256(archive) == LIBVPX_ARCHIVE_SHA256;
        if curl_ok && checksum_ok {
            return;
        }
        last_error = format!(
            "attempt {attempt}: curl ok={curl_ok}, checksum ok={checksum_ok}"
        );
        eprintln!("libvpx download retry: {last_error}");
        std::thread::sleep(std::time::Duration::from_secs(5 * attempt));
    }
    panic!("failed to download libvpx after retries: {last_error}");
}

fn configure_android_libvpx(source: &Path, build: &Path) {
    let rust_target = env::var("TARGET").expect("TARGET is missing");
    let vpx_target = match rust_target.as_str() {
        "aarch64-linux-android" => "arm64-android-gcc",
        "armv7-linux-androideabi" => "armv7-android-gcc",
        "x86_64-linux-android" => "x86_64-android-gcc",
        "i686-linux-android" => "x86-android-gcc",
        other => panic!("unsupported Android target for libvpx: {other}"),
    };
    let cc = target_tool("CC", &rust_target);
    let cxx = target_tool("CXX", &rust_target);
    let ar = target_tool("AR", &rust_target);
    let toolchain = cc
        .parent()
        .expect("Android compiler has no parent directory");
    // Windows 上 configure 是 #!/bin/sh 脚本，CreateProcess 不能直接执行（错误 193），
    // 必须显式交给 sh；同时 sh 只认正斜杠，参数里的路径先做转换。
    let mut cmd = if cfg!(windows) {
        let mut c = Command::new(shell_program());
        c.arg(posix_path(&source.join("configure")));
        c
    } else {
        Command::new(source.join("configure"))
    };
    let status = cmd
        .arg(format!("--target={vpx_target}"))
        .args([
            "--disable-examples",
            "--disable-tools",
            "--disable-docs",
            "--disable-unit-tests",
            "--disable-install-bins",
            "--disable-install-docs",
            "--disable-webm-io",
            "--disable-libyuv",
            "--disable-vp8",
            "--disable-vp9-encoder",
            "--enable-vp9-decoder",
            "--enable-pic",
            "--enable-small",
        ])
        .env("CC", &cc)
        .env("CXX", &cxx)
        .env("LD", &cc)
        .env("AR", &ar)
        .env("AS", &cc)
        .env("NM", toolchain.join("llvm-nm"))
        .env("STRIP", toolchain.join("llvm-strip"))
        .current_dir(build)
        .status()
        .expect("failed to configure libvpx");
    assert!(status.success(), "failed to configure libvpx");
}

/// 执行 `#!` 脚本用的 shell。Windows 上由构建环境把 Git 的 `usr/bin` 放进 PATH，
/// 也可以用 `WCX_SH` 显式指定。
fn shell_program() -> PathBuf {
    env::var_os("WCX_SH")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            if cfg!(windows) {
                PathBuf::from("sh")
            } else {
                PathBuf::from("/bin/sh")
            }
        })
}

fn posix_path(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}

fn target_tool(prefix: &str, target: &str) -> PathBuf {
    let candidates = [
        format!("{prefix}_{target}"),
        format!("{prefix}_{}", target.replace('-', "_")),
        prefix.to_string(),
    ];
    candidates
        .iter()
        .find_map(|key| env::var_os(key))
        .map(PathBuf::from)
        .unwrap_or_else(|| panic!("{prefix} is not configured for {target}"))
}

fn file_sha256(path: &Path) -> String {
    let bytes = fs::read(path).expect("failed to read downloaded libvpx archive");
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}
