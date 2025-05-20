# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

### Basic Build and Run Commands

```bash
# Build the project
./gradlew build

# Build with spotless formatting applied
./gradlew dev

# Install the distribution locally
./gradlew installDist

# Run Besu with the local installation
./build/install/besu/bin/besu [OPTIONS]

# Run a specific command with Besu
./build/install/besu/bin/besu [COMMAND] [OPTIONS]
```

### Test Commands

```bash
# Run unit tests
./gradlew test

# Run integration tests
./gradlew integrationTest 

# Run acceptance tests
./gradlew acceptanceTest

# Run specific acceptance test types
./gradlew acceptanceTestCliqueBft       # Runs Clique and BFT tests
./gradlew acceptanceTestPermissioning   # Runs permissioning tests
./gradlew acceptanceQbftTest            # Runs QBFT tests

# Run tests with specific parameters
# For instance, to run a single Ethereum reference test:
./gradlew :ethereum:org.hyperledger.besu.ethereum.vm:test -Dtest.single=GeneralStateTest -Dtest.ethereum.include=callcodecallcallcode_101-Frontier

# To run general state tests for a specific milestone:
./gradlew :ethereum:org.hyperledger.besu.ethereum.vm:test -Dtest.single=GeneralStateTest -Dtest.ethereum.state.eip=Frontier
```

### Code Quality Commands

```bash
# Run spotless check
./gradlew spotlessCheck

# Apply spotless formatting
./gradlew spotlessApply

# Check licenses
./gradlew checkLicense

# Generate Javadoc
./gradlew javadoc
```

## Project Architecture

Hyperledger Besu is an Ethereum client written in Java. It's designed as a modular and extensible implementation of the Ethereum protocol with support for public, private, and consortium networks.

### Key Modules

- **besu**: Core application module that ties everything together and provides the CLI interface
- **config**: Network configuration including genesis data for different networks
- **consensus**: Implementations of consensus protocols (QBFT, IBFT, Clique, etc.)
- **crypto**: Cryptographic utilities and implementations
- **ethereum**: Core Ethereum protocol implementation
  - **api**: JSON-RPC APIs
  - **blockcreation**: Block creation and mining
  - **core**: Core Ethereum data structures and processing
  - **eth**: Ethereum subprotocol implementation
  - **p2p**: Peer-to-peer networking
  - **trie**: Merkle Patricia Trie implementation
- **evm**: Ethereum Virtual Machine implementation
- **metrics**: Metrics and monitoring
- **plugins**: Plugin system for extending functionality
- **privacy**: Privacy features (deprecated Tessera-based privacy)

### Key Components

1. **CLI Interface**: The entry point for Besu, processing command-line options and configuration files
2. **P2P Network**: Manages peer connections and message passing
3. **Blockchain Processor**: Validates and processes blocks
4. **Transaction Pool**: Manages pending transactions
5. **JSON-RPC API**: Provides interfaces for external applications
6. **EVM**: Executes smart contract code
7. **Database**: Stores blockchain data using a pluggable storage system

## Development Guidelines

- Java 21 is required for building and running Besu
- The project uses Gradle for build management
- Code formatting is done using Spotless with Google Java Format (1.25.2)
- JUnit 5 is used for testing
- The project includes several test suites:
  - Unit tests
  - Integration tests
  - Acceptance tests
  - Reference tests (for Ethereum protocol compliance)

When submitting code:
- Ensure all tests pass
- Apply proper formatting using Spotless
- Follow Java coding conventions
- Ensure proper Javadoc comments are included
- Observe the project's license requirements

## Configuration Options

Besu can be configured via:
1. Command-line arguments
2. Environment variables (prefixed with BESU_)
3. TOML configuration files

Main configuration categories:
- Network selection (mainnet, sepolia, etc.) or custom genesis file
- Node identity and P2P networking
- Data storage
- JSON-RPC APIs
- Consensus algorithm parameters
- Metrics and monitoring
- Transaction pool settings
- Logging options