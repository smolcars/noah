<p align="center">
  <img src="client/assets/All_Files/all_sizes/1024.png" alt="Noah Logo" width="80"/>
</p>

<h1 align="center">Noah's Ark</h1>

<p align="center">Noah is a modern, trust-minimized wallet for Ark, a Bitcoin Layer 2 protocol. It is built with React Native and Expo.</p>

<p align="center">
  ⚠️ <strong>WARNING</strong>: This project is in beta. Only deposit funds you are ok with losing.
</p>

---

## Table of Contents

- [✨ Core Technologies](#-core-technologies)
- [🚀 Getting Started](#-getting-started)
  - [Using Nix (Recommended)](#using-nix-recommended)
  - [Bare Expo Setup](#bare-expo-setup)
- [⚡️ Local Ark Regtest Environment](#️-local-ark-regtest-environment)
- [🏃 Running the Application](#-running-the-application)
- [📦 Building for Production](#-building-for-production)
- [📜 License](#-license)

---

## ✨ Core Technologies

- **Framework**: React Native & Expo
- **Runtime & Package Manager**: Bun
- **Language**: TypeScript
- **Styling**: NativeWind (Tailwind CSS for React Native)
- **State Management**: Zustand
- **Navigation**: React Navigation
- **Data Fetching**: TanStack Query
- **Local Storage**: MMKV
- **Native Modules**: Nitro (Ark)
- **Development Environment**: Nix
- **Server**: Rust (Axum + Postgres + Redis/Dragonfly cache)

---

## 🚀 Getting Started with the app

You can set up the development environment using Nix (recommended) or by manually installing the dependencies.

### Using Nix (Recommended)

This project uses [Nix](https://nixos.org/) to provide a reproducible development environment. While most dependencies are managed by Nix, you will still need to install a few tools manually.

**Prerequisites:**

1.  **Install Nix**: Follow the [official installation guide](https://docs.determinate.systems/).
2.  **Install direnv**: This tool will automatically load the Nix environment when you enter the project directory. Follow the [direnv installation guide](https://direnv.net/docs/installation.html).
3.  **Hook direnv into your shell**: Make sure to follow the instructions to hook direnv into your shell (e.g., add `eval "$(direnv hook zsh)"` to your `.zshrc`).
4.  **Install IDEs and SDKs**:
    - **Android**: Install [Android Studio](https://developer.android.com/studio).
    - **iOS (macOS only)**: Install [Xcode](https://developer.apple.com/xcode/) from the Mac App Store.

**Setup:**

1.  **Clone the Repository**

    ```bash
    git clone https://github.com/smolcars/noah.git
    cd noah
    ```

2.  **Allow direnv to load the environment**
    This command will trigger Nix to build the development shell. It might take a while on the first run.

    ```bash
    direnv allow
    ```

3.  **Install JavaScript Dependencies**
    Once the Nix shell is active, you can install the project's dependencies.

    ```bash
    just install
    ```

4.  **Install iOS Dependencies (for macOS users)**
    This step links the native iOS libraries.
    ```bash
    just ios-prebuild
    ```

Now the project is ready to run.

### Bare Expo Setup

If you prefer not to use Nix, you can set up your environment manually. This project is a bare Expo project.

For a comprehensive guide on setting up your machine for bare Expo development, please refer to the **[Expo documentation](https://docs.expo.dev/get-started/set-up-your-environment/?mode=development-build&platform=android&device=simulated)**. This includes installing Node.js, Watchman, the Java Development Kit, Android Studio, and Xcode.

Once your environment is set up, follow these steps:

1.  **Clone the Repository**

    ```bash
    git clone https://github.com/smolcars/noah.git
    cd noah
    ```

2.  **Install JavaScript Dependencies**

    ```bash
    just install
    ```

3.  **Install iOS Dependencies (for macOS users)**
    ```bash
    just ios-prebuild
    ```

---

## ⚡️ Local Ark Regtest Environment

For development and testing, you can run a complete local Ark stack using Docker Compose. The environment includes:

- **bitcoind** - Bitcoin Core in regtest mode
- **captaind** (aspd) - Ark Server Protocol Daemon
- **bark** - Ark CLI client
- **postgres** - Database for captaind
- **dragonfly** - Redis-compatible cache backing LNURL auth (k1) storage
- **cln** - Core Lightning node
- **lnd** - Lightning Network Daemon
- **noah-server** - Noah backend server

The [Dev helper script](scripts/ark-dev.sh) script helps manage this environment.

**Prerequisites:**

- **Docker**: Install [Docker Desktop](https://www.docker.com/products/docker-desktop/).
- **jq**: Command-line JSON processor. Install via your package manager (e.g., `brew install jq` on macOS).

**Quick Start - Complete Setup:**

Run the automated setup script that will start all services, create wallets, mine blocks, fund the Ark server, and set up Lightning channels:

```bash
just setup-everything
```

This single command will:

- Start all Docker services (bitcoind, captaind, postgres, dragonfly db, cln, lnd, bark, noah-server)
- Create and fund a Bitcoin Core wallet
- Generate 150 blocks
- Fund the Ark server with 1 BTC
- Create a bark wallet
- Funds the bark wallet with 0.1 BTC and boards into Ark with 0.01 BTC
- Fund LND with 0.1 BTC
- Open a Lightning channel between LND and CLN (1M sats, 900k pushed to CLN)

**Manual Setup (Step by Step):**

1.  **Start all services**

    ```bash
    just up
    ```

2.  **Create and fund wallets**

    ```bash
    # Create a Bitcoin Core wallet
    just create-wallet

    # Generate blocks to fund it
    just generate 150

    # Fund the Ark server
    just fund-aspd 1

    # Create a bark wallet
    just create-bark-wallet
    ```

3.  **Setup Lightning channels (optional)**
    ```bash
    just setup-lightning-channels
    ```

**Managing Services:**

```bash
# Stop services (keeps data)
just stop

# Stop and delete all data
just down
```

**Useful Commands:**

- Interact with bark wallet: `just bark <command>`
- Interact with ASPD RPC: `just aspd <command>`
- Use bitcoin-cli: `just bcli <command>`
- Use lncli: `just lncli <command>`
- Use lightning-cli (CLN): `just cln <command>`
- Generate blocks: `just generate <num_blocks>`
- Send to address: `just send-to <address> <amount>`

**Service Endpoints:**

- Bitcoin Core RPC: `http://localhost:18443`
- Ark Server (captaind): `http://localhost:3535`
- Noah Server: `http://localhost:3000`
- Noah Server Health: `http://localhost:3099/health`
- PostgreSQL: `localhost:5432`
- LND RPC: `localhost:10009` (P2P: `localhost:9735`)
- CLN RPC: `localhost:9988` (P2P: `localhost:9736`)

For more commands and details, run `just` without arguments.

---

## 🏃 Running the Application

This project uses [just](https://github.com/casey/just) commands to run the application in different environments (Mainnet, Signet, Regtest).

For a full list of available commands, run:

```bash
just
```

**Example (running on Android Regtest):**

```bash
just android
# or
just android-regtest
```

**Example (running on iOS Regtest):**

```bash
just ios
# or
just ios-regtest
```

**Other useful commands:**

```bash
just check              # Run type checking and linting
just ios-prebuild       # Install iOS dependencies
just clean-all          # Clean all build artifacts
just server             # Run server with hot reload (bacon)
just test               # Run server tests
```

## 📡 Running the server

**Important note:** Like written above, `just setup-everything` will setup PostgreSQL and Redis instances for you but it also starts up the server, so if you are working on the server, simply stop the docker container of `noah-server` and run `just server` to start the server manually, this will compile the Rust code and start the server locally instead of using Docker.

### Configuration Setup

The server uses environment variables for configuration. For local development, create a `.env` file in the project root.

1. **Create a `.env` file in the project root:**

   ```bash
   cp .env.example .env
   ```

2. **Edit `.env` with your values:**

   ```bash
   HOST=0.0.0.0
   PORT=3000
   PRIVATE_PORT=3099
   LNURL_DOMAIN=localhost
   POSTGRES_URL=postgres://postgres:postgres@localhost:5432/noah
   POSTGRES_MAX_CONNECTIONS=10
   REDIS_URL=redis://127.0.0.1:6379
   EXPO_ACCESS_TOKEN=your-expo-access-token # Can set junk value for local development
   ARK_SERVER_URL=http://localhost:3535
   SERVER_NETWORK=regtest
   BACKUP_CRON="every 2 hours"
   S3_BUCKET_NAME=noah-regtest-backups # Can set junk value for local development
   MINIMUM_APP_VERSION=0.0.1

   # AWS credentials for S3
   # Can set junk value for local development
   AWS_ACCESS_KEY_ID=your-aws-access-key-id
   AWS_SECRET_ACCESS_KEY=your-aws-secret-access-key
   AWS_REGION=us-east-2

   # This needs to be true in local development
   EMAIL_DEV_MODE=true
   AUTH_JWT_SECRET=dont_use_this_you_will_get_screwed
   ```

### Running

- If you're using Nix, simply run `bacon` to start a hot reloading Rust.
- If you are not using Nix, then `cargo install bacon` for hot reloading and then run `bacon`.
- If you just want to run the server `cargo run` or `cargo run --release`.
- For release builds, run `cargo build --release`.

---

## 📦 Building for Production

You can create production-ready application binaries using just commands:

**Android Production Builds:**

```bash
just android-regtest-release
just android-signet-release
just android-mainnet-release
```

**iOS Production Builds:**

```bash
just ios-regtest-release
just ios-signet-release
just ios-mainnet-release
```

For a complete list of build commands, run `just` to see all available recipes.

**Note on Code Signing:** For production builds, you will need to configure your own signing keys. Refer to the official React Native and Expo documentation for code signing on [Android](https://reactnative.dev/docs/signed-apk-android) and iOS.

---

## 📜 License

This project is licensed under the MIT License.
