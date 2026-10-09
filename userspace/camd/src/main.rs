#![deny(clippy::all, clippy::pedantic)]
#![warn(clippy::nursery)]
#![allow(
    clippy::module_name_repetitions,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    clippy::doc_markdown,
    clippy::too_many_lines,
    clippy::cast_possible_wrap,
    clippy::redundant_field_names
)]

mod allow;
mod assets;
mod boot_guard;
mod boot_patch;
#[cfg(target_os = "android")]
mod cli;
#[cfg(not(target_os = "android"))]
mod cli_non_android;
#[cfg(target_os = "android")]
mod debug;
mod defs;
#[cfg(target_os = "android")]
mod feature;
mod hide_bootloader;
mod hiding_audit;
#[cfg(target_os = "android")]
mod init_event;
#[cfg(target_os = "android")]
mod jailbreak;
#[cfg(target_os = "android")]
mod ksucalls;
#[cfg(target_os = "android")]
mod late_load;
#[cfg(target_os = "android")]
mod legacy;
mod lkm_image;
mod lkm_image_btf;
#[cfg(target_os = "android")]
mod metamodule;
#[cfg(target_os = "android")]
mod module;
#[cfg(target_os = "android")]
mod module_config;
mod module_conflicts;
#[cfg(target_os = "android")]
mod profile;
#[cfg(target_os = "android")]
mod resetprop;
#[cfg(target_os = "android")]
mod restorecon;
mod seed;
#[cfg(target_os = "android")]
mod sepolicy;
#[cfg(target_os = "android")]
mod soft_reboot;
#[cfg(target_os = "android")]
mod su;
#[cfg(target_os = "android")]
mod sulog;
#[cfg(target_os = "android")]
mod unload;
#[cfg(target_os = "android")]
mod utils;

#[cfg(target_os = "android")]
#[allow(nonstandard_style, unused, unsafe_op_in_unsafe_fn)]
mod ksu_uapi;

fn main() -> anyhow::Result<()> {
    #[cfg(target_os = "android")]
    {
        cli::run()
    }
    #[cfg(not(target_os = "android"))]
    {
        cli_non_android::run()
    }
}
