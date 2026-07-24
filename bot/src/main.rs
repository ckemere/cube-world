//! CubeWorld test bot (azalea, Minecraft 26.2 / protocol 776).
//!
//! Milestone 1: log in to the local cube server as an offline player and
//! continuously report position + dimension. Dimension reporting is the
//! instrument we need for the eventual portal round-trip test — it lets us see,
//! from outside the game, exactly when the bot crosses overworld <-> nether and
//! where it lands on each side.
//!
//! Run against the dev server (online-mode=false) with:
//!   PATH="$HOME/.cargo/bin:$PATH" cargo run

use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};

use azalea::prelude::*;
use azalea_world::WorldName;
use parking_lot::Mutex;

#[tokio::main]
async fn main() -> AppExit {
    let account = Account::offline("PortalTester");
    println!("[bot] connecting to localhost:25565 as PortalTester...");
    ClientBuilder::new()
        .set_handler(handle)
        .start(account, "localhost:25565")
        .await
}

#[derive(Clone, Component, Default)]
struct State {
    ticks: Arc<AtomicI64>,
    last_dim: Arc<Mutex<String>>,
}

/// Current dimension as a plain string like "minecraft:the_nether", or a marker
/// if the component isn't attached yet (e.g. before the first spawn).
fn dim_of(bot: &Client) -> String {
    match bot.component::<WorldName>() {
        Ok(w) => format!("{}", *w),
        Err(_) => "<none>".to_string(),
    }
}

async fn handle(bot: Client, event: Event, state: State) -> eyre::Result<()> {
    match event {
        Event::Login => println!("[bot] LOGIN ok — handshake accepted (26.2)"),
        Event::Spawn => {
            let pos = bot.position()?;
            let dim = dim_of(&bot);
            *state.last_dim.lock() = dim.clone();
            println!("[bot] SPAWN at ({:.1}, {:.1}, {:.1}) in {dim}", pos.x, pos.y, pos.z);
        }
        Event::Tick => {
            let n = state.ticks.fetch_add(1, Ordering::Relaxed);
            let dim = dim_of(&bot);
            // Announce any dimension change the instant it happens.
            {
                let mut last = state.last_dim.lock();
                if *last != dim && dim != "<none>" {
                    if let Ok(pos) = bot.position() {
                        println!("[bot] *** DIMENSION CHANGE: {} -> {dim} at ({:.1}, {:.1}, {:.1})",
                            *last, pos.x, pos.y, pos.z);
                    }
                    *last = dim.clone();
                }
            }
            // Heartbeat position report once per second.
            if n % 20 == 0 {
                if let Ok(pos) = bot.position() {
                    println!("[bot] t={:>4}s pos=({:.1}, {:.1}, {:.1}) dim={dim}",
                        n / 20, pos.x, pos.y, pos.z);
                }
            }
        }
        Event::Chat(m) => println!("[bot] CHAT: {}", m.message().to_ansi()),
        Event::Death(_) => println!("[bot] DEATH"),
        Event::Disconnect(reason) => println!("[bot] DISCONNECT: {reason:?}"),
        _ => {}
    }
    Ok(())
}
