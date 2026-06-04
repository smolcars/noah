#!/bin/bash

# A complete bootstrap and management script for the Ark dev environment.
#
# Exit immediately if a command exits with a non-zero status.
set -e

# --- Configuration ---
# This script uses the local docker-compose.yml file
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

BITCOIND_SERVICE="bitcoind"
ASPD_SERVICE="captaind"
BARK_SERVICE="bark"
CLN_SERVICE="cln"
LND_SERVICE="lnd"
NOAH_SERVER_SERVICE="noah-server"

# Bitcoin Core wallet name to be used by this script
WALLET_NAME="dev-wallet"

# Bitcoin-cli options (matches the user/pass from the docker-compose.yml)
BITCOIN_CLI_OPTS="-regtest -rpcuser=second -rpcpassword=ark -rpcwallet=$WALLET_NAME"
# --- End Configuration ---
    echo "SETUP:"
    echo "  setup                      Clone the bark repo and checkout the correct version."
    echo "  setup-everything           Run complete setup: setup, up, create-wallet, generate 150, fund-aspd 1, create-bark-wallet, start noah-server."


# Helper function to avoid repeating the long docker-compose command
dcr() {
    docker-compose -f "$COMPOSE_FILE" "$@"
}

# --- Functions ---

# Displays how to use the script
usage() {
    echo "A bootstrap and management script for the Ark dev environment."
    echo ""
    echo "Usage: $0 <command> [arguments]"
    echo ""
    echo "SETUP:"
    echo "  setup                      Clone the bark repo and checkout the correct version."
    echo "  setup-everything           Run complete setup: setup, up, create-wallet, generate 150, fund-aspd 1, create-bark-wallet, start noah-server."
    echo ""
    echo "LIFECYCLE COMMANDS (run after 'setup'):"
    echo "  up                         Start all services in the background (docker-compose up -d)."
    echo "  stop                       Stop all services (docker-compose stop)."
    echo "  down                       Stop and remove all services (docker-compose down) and noah-server."
    echo ""
    echo "MANAGEMENT COMMANDS (run while services are 'up'):"
    echo "  create-wallet              Create and load a new wallet in bitcoind named '$WALLET_NAME'."
    echo "  create-bark-wallet         Create a new bark wallet with pre-configured dev settings."
    echo "  generate <num_blocks>      Mine blocks on bitcoind. Creates wallet if it doesn't exist."
    echo "  fund-aspd <amount>         Send <amount> of BTC from bitcoind to the ASPD wallet."
    echo "  send-to <addr> <amt>       Send <amt> BTC from bitcoind to <addr> and mine 1 block."
    echo "  aspd <args...>             Execute a command on the running aspd container."
    echo "  bark <args...>             Execute a command on a new bark container."
    echo "  setup-lightning-channels   Fund LND and open channel from LND to CLN."
    echo "  lncli <args...>            Execute lncli commands on the running LND container."
    echo "  cln <args...>              Execute lightning-cli commands on the running CLN container."
}


# Creates a new wallet in bitcoind if it doesn't already exist
create_wallet() {
    echo "Checking for bitcoind wallet '$WALLET_NAME'..."
    if dcr exec "$BITCOIND_SERVICE" bitcoin-cli -regtest -rpcuser=second -rpcpassword=ark listwallets | grep -q "\"$WALLET_NAME\""; then
        echo "✅ Wallet '$WALLET_NAME' already exists."

    # Else if attempt to load wallet
    elif dcr exec "$BITCOIND_SERVICE" bitcoin-cli -regtest -rpcuser=second -rpcpassword=ark loadwallet "$WALLET_NAME"; then
        echo "✅ Wallet '$WALLET_NAME' loaded successfully."

    elif dcr exec "$BITCOIND_SERVICE" bitcoin-cli -regtest -rpcuser=second -rpcpassword=ark createwallet "$WALLET_NAME"; then
        echo "✅ Wallet '$WALLET_NAME' created successfully."

    else
        echo "Failed to create wallet '$WALLET_NAME'."
    fi
}

# Creates a new bark wallet with default dev settings
create_bark_wallet() {
    echo "Creating a new bark wallet with dev settings..."

    # Remove existing bark directory to allow fresh creation with new flags
    echo "🧹 Cleaning existing bark data if present..."
    docker run --rm -v scripts_bark:/data alpine sh -c "rm -rf /data/.bark" 2>/dev/null || true

    dcr run --rm "$BARK_SERVICE" bark create \
        --regtest \
        --ark http://captaind:3535 \
        --bitcoind http://bitcoind:18443 \
        --bitcoind-user second \
        --bitcoind-pass ark \
        --force
    echo "✅ Bark wallet created. You can now use './ark-dev.sh bark <command>'."
}

# Generates blocks using the bitcoind container
generate_blocks() {
    local blocks="$1"
    create_wallet

    echo "⛏️  Generating $blocks blocks on bitcoind..."
    local address
    address=$(dcr exec "$BITCOIND_SERVICE" bitcoin-cli $BITCOIN_CLI_OPTS getnewaddress)
    dcr exec "$BITCOIND_SERVICE" bitcoin-cli $BITCOIN_CLI_OPTS generatetoaddress "$blocks" "$address"
    echo "✅ Done. $blocks blocks generated."
}

# Sends funds from the bitcoind wallet to a specified address
send_to_address() {
    local address="$1"
    local amount="$2"
    create_wallet

    echo "➡️  Sending $amount BTC to address: $address..."
    local txid
    txid=$(dcr exec "$BITCOIND_SERVICE" bitcoin-cli $BITCOIN_CLI_OPTS sendtoaddress "$address" "$amount")
    echo "💸 Transaction sent with TXID: $txid"

    echo "Confirming transaction..."
    generate_blocks 1
}

# Funds the ASPD wallet from the bitcoind wallet
fund_aspd() {
    local amount="$1"

    if ! command -v jq &> /dev/null; then
        echo "Error: 'jq' is not installed. Please install it to continue." >&2
        exit 1
    fi

    echo "🔍 Getting ASPD wallet address..."
    local aspd_address
    aspd_address=$(dcr exec "$ASPD_SERVICE" "$ASPD_SERVICE" rpc wallet 2>/dev/null | grep -A 100 '^{' | jq -r '.rounds.address')

    if [[ -z "$aspd_address" || "$aspd_address" == "null" ]]; then
        echo "Error: Could not retrieve ASPD wallet address. Is the aspd container running?" >&2
        exit 1
    fi

    send_to_address "$aspd_address" "$amount"
}

# Sets up Lightning Network channels between LND and CLN
setup_lightning_channels() {
    echo "⚡ Setting up Lightning Network channels..."

    if ! command -v jq &> /dev/null; then
        echo "Error: 'jq' is not installed. Please install it to continue." >&2
        exit 1
    fi

    echo ""
    echo "⏳ Waiting for LND to fully start..."
    local retries=20
    local count=0
    until dcr exec "$LND_SERVICE" lncli --network=regtest getinfo &> /dev/null; do
        count=$((count+1))
        if [ $count -ge $retries ]; then
            echo "Error: LND did not start within the expected time." >&2
            exit 1
        fi
        echo "   (waiting for lnd to be ready...)"
        sleep 2
    done

    echo ""
    echo "🔍 Getting LND node pubkey..."
    local lnd_pubkey
    lnd_pubkey=$(dcr exec "$LND_SERVICE" lncli --network=regtest getinfo | jq -r '.identity_pubkey')
    echo "   LND pubkey: $lnd_pubkey"

    echo ""
    echo "🔍 Getting CLN node pubkey..."
    local cln_pubkey
    cln_pubkey=$(dcr exec "$CLN_SERVICE" lightning-cli --regtest getinfo | jq -r '.id')
    echo "   CLN pubkey: $cln_pubkey"

    echo ""
    echo "💰 Generating new address on LND node..."
    local lnd_address
    lnd_address=$(dcr exec "$LND_SERVICE" lncli --network=regtest newaddress p2tr | jq -r '.address')
    echo "   Address: $lnd_address"

    echo ""
    echo "💸 Sending 0.1 BTC to LND address..."
    send_to_address "$lnd_address" "0.1"

    echo ""
    echo "⛏️  Generating 10 blocks..."
    generate_blocks 10

    echo ""
    echo "⏳ Waiting for LND to sync to chain..."
    local sync_retries=30
    local sync_count=0
    until dcr exec "$LND_SERVICE" lncli --network=regtest getinfo | jq -e '.synced_to_chain == true' &> /dev/null; do
        sync_count=$((sync_count+1))
        if [ $sync_count -ge $sync_retries ]; then
            echo "Error: LND did not sync to chain within the expected time." >&2
            exit 1
        fi
        echo "   (waiting for lnd to sync...)"
        sleep 2
    done
    echo "   ✅ LND is synced to chain"

    echo ""
    echo "🔗 Connecting LND to CLN..."
    dcr exec "$CLN_SERVICE" lightning-cli --regtest connect "$lnd_pubkey@lnd:9735" || echo "   (Already connected or connection failed, continuing...)"

    echo ""
    echo "⚡ Opening channel from LND to CLN (1,000,000 sats with 900,000 push amount)..."
    dcr exec "$LND_SERVICE" lncli --network=regtest openchannel "$cln_pubkey" 1000000 900000

    echo ""
    echo "⛏️  Generating 10 more blocks to confirm channel..."
    generate_blocks 10

    echo "✅ Lightning Network channels setup complete!"
}

# Runs the complete setup sequence
setup_everything() {
    echo "🚀 Running complete setup sequence..."

    echo "🚀 Starting all services..."
    dcr up -d

    echo ""
    echo "⏳ Waiting for services to be ready..."
    sleep 15

    echo ""
    create_wallet

    echo ""
    generate_blocks 150

    echo ""
    fund_aspd 5

    echo ""
    create_bark_wallet

    echo ""
    echo "🔍 Getting bark onchain address..."
    local bark_address
    bark_address=$(dcr run --rm "$BARK_SERVICE" bark onchain address | jq -r '.address')
    echo "   Bark address: $bark_address"

    echo ""
    echo "💸 Sending 0.1 BTC to bark wallet..."
    send_to_address "$bark_address" "0.1"

    echo ""
    echo "⛏️  Generating 6 blocks..."
    generate_blocks 6

    echo ""
    echo "⏳ Waiting 5 seconds before boarding onto Ark..."
    sleep 5

    echo ""
    echo "🚢 Boarding onto Ark with 1000000 sats..."
    dcr run --rm "$BARK_SERVICE" bark board '1000000 sats'

    echo ""
    echo "⛏️  Generating 6 more blocks to confirm boarding..."
    generate_blocks 6

    echo ""
    setup_lightning_channels

    echo ""
    echo "🎉 Complete setup finished successfully!"
    echo "Your Ark dev environment is ready to use."
    echo ""
    echo "Services running:"
    echo "  - Bitcoin Core (regtest): http://localhost:18443"
    echo "  - ASPD (Ark Server): http://localhost:3535"
    echo "  - Noah Server: http://localhost:3000"
    echo "  - Noah Server Health: http://localhost:3099/health"
    echo "  - LND (Lightning): RPC at localhost:10009, P2P at localhost:9735"
    echo "  - CLN (Core Lightning): RPC at localhost:9988, P2P at localhost:9736"
    echo "  - Lightning Channel: LND <-> CLN (1M sats with 900k pushed to CLN)"
}

# --- Main Logic ---

COMMAND=$1

if [[ -z "$COMMAND" ]]; then
    usage
    exit 1
fi

# Ensure the docker-compose file exists
if [[ "$COMMAND" != "setup-everything" && ! -f "$COMPOSE_FILE" ]]; then
    echo "Error: docker-compose.yml not found at '$COMPOSE_FILE'." >&2
    exit 1
fi

shift

case "$COMMAND" in
    setup-everything)
        setup_everything
        ;;

    up)
        echo "🚀 Starting all services in the background..."
        dcr up -d "$@"
        ;;

    stop)
        echo "🛑 Stopping all services..."
        dcr stop "$@"
        ;;

    down)
        echo "🛑 Stopping and removing all services..."
        dcr down "$@" --volumes
        ;;

    create-wallet)
        create_wallet
        ;;

    create-bark-wallet)
        create_bark_wallet
        ;;

    generate)
        num_blocks=${1:-101}
        generate_blocks "$num_blocks"
        ;;

    fund-aspd)
        if [[ -z "$1" ]]; then
            echo "Error: Please provide an amount to send." >&2; usage; exit 1
        fi
        fund_aspd "$1"
        ;;

    send-to)
        if [[ -z "$1" || -z "$2" ]]; then
            echo "Error: Please provide both an address and an amount." >&2; usage; exit 1
        fi
        send_to_address "$1" "$2"
        ;;

    aspd)
        echo "Running command on aspd: $@"
        dcr exec "$ASPD_SERVICE" "$ASPD_SERVICE" "$@"
        ;;

    bark)
        echo "Running command on bark: bark $@"
        dcr run --rm "$BARK_SERVICE" "bark" "$@"
        ;;

    bcli)
        echo "Running bitcoin-cli command: $@"
        dcr exec "$BITCOIND_SERVICE" bitcoin-cli $BITCOIN_CLI_OPTS "$@"
        ;;

    setup-lightning-channels)
        setup_lightning_channels
        ;;

    lncli)
        echo "Running lncli command: $@"
        dcr exec "$LND_SERVICE" lncli --network=regtest "$@"
        ;;

    cln)
        echo "Running lightning-cli command: $@"
        dcr exec "$CLN_SERVICE" lightning-cli --regtest "$@"
        ;;

    *)
        echo "Error: Unknown command '$COMMAND'" >&2
        usage
        exit 1
        ;;
esac

# Don't print success message for passthrough or lifecycle commands
if [[ "$COMMAND" != "aspd" && "$COMMAND" != "bark" && "$COMMAND" != "up" && "$COMMAND" != "down" ]]; then
    echo "🎉 Script finished successfully."
fi
