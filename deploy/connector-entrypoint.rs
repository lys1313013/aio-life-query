use std::env;
use std::os::unix::process::CommandExt;
use std::process::{self, Command};

fn main() {
    let arguments: Vec<String> = env::args().skip(1).collect();
    let serving = arguments.first().is_some_and(|value| value == "serve");
    let help = arguments.iter().any(|value| value == "--help" || value == "--version");
    if serving && !help {
        if env::var("HASURA_SERVICE_TOKEN_SECRET").map_or(true, |value| value.len() < 32) {
            eprintln!("HASURA_SERVICE_TOKEN_SECRET must contain at least 32 characters");
            process::exit(64);
        }
        if env::var("CONNECTION_URI").map_or(true, |value| value.is_empty()) {
            eprintln!("CONNECTION_URI is required");
            process::exit(64);
        }
    }
    let error = Command::new("/bin/ndc-postgres").args(arguments).exec();
    eprintln!("Cannot start PostgreSQL connector: {error}");
    process::exit(70);
}
