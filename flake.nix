{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      android-nixpkgs,
      rust-overlay,
    }:
    let
      systems = [
        "aarch64-darwin"
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;

      pkgsFor =
        system:
        import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
          overlays = [ rust-overlay.overlays.default ];
        };

      androidSdkFor =
        system:
        android-nixpkgs.sdk.${system} (
          sdkPkgs: with sdkPkgs; [
            cmdline-tools-latest
            build-tools-35-0-0
            build-tools-36-0-0
            platform-tools
            platforms-android-35
            platforms-android-36
            ndk-27-1-12297006
            ndk-27-0-12077973
            cmake-3-22-1
          ]
        );

      # macOS-specific derivations
      darwinDerivations = {
        xcode-wrapper =
          pkgs:
          pkgs.stdenv.mkDerivation {
            name = "xcode-wrapper-16.4.0";
            buildInputs = [ pkgs.darwin.cctools ];
            buildCommand = ''
              mkdir -p $out/bin

              # Create wrapper scripts instead of symlinks
              cat > $out/bin/xcodebuild << EOF
              #!/bin/sh
              exec /usr/bin/xcodebuild "\$@"
              EOF

              cat > $out/bin/xcrun << EOF
              #!/bin/sh
              exec /usr/bin/xcrun "\$@"
              EOF

              cat > $out/bin/xcode-select << EOF
              #!/bin/sh
              if [ "\$1" = "-p" ] && [ -n "\$DEVELOPER_DIR" ]; then
                echo "\$DEVELOPER_DIR"
              else
                exec /usr/bin/xcode-select "\$@"
              fi
              EOF

              cat > $out/bin/codesign << EOF
              #!/bin/sh
              exec /usr/bin/codesign "\$@"
              EOF

              cat > $out/bin/ld << EOF
              #!/bin/sh
              exec /usr/bin/ld "\$@"
              EOF

              cat > $out/bin/clang << EOF
              #!/bin/sh
              exec /usr/bin/clang "\$@"
              EOF

              chmod +x $out/bin/*

              if [ -d "/Applications/Xcode.app" ]; then
                DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
              elif [ -d "/Applications/Xcode-16.4.0.app" ]; then
                DEVELOPER_DIR="/Applications/Xcode-16.4.0.app/Contents/Developer"
              else
                echo "Error: Xcode not found"
                exit 1
              fi

              echo "export DEVELOPER_DIR=\"$DEVELOPER_DIR\"" > $out/bin/env.sh
            '';
          };

      };

      # System-specific shell configuration
      mkShellFor =
        system:
        let
          pkgs = pkgsFor system;
          androidSdk = androidSdkFor system;
          basePackages = with pkgs; [
            androidSdk
            bacon
            bun
            grpcurl
            just
            rust-analyzer
          ];

          darwinPackages = with pkgs; [
            bundler
            cocoapods
            (darwinDerivations.xcode-wrapper pkgs)
            maestro
          ];

          darwinHook = ''
            export LC_ALL=en_US.UTF-8
            export LANG=en_US.UTF-8
            export JAVA_HOME="${pkgs.jdk17.home}"

            unset SDKROOT

            if [ -f "${darwinDerivations.xcode-wrapper pkgs}/bin/env.sh" ]; then
              source "${darwinDerivations.xcode-wrapper pkgs}/bin/env.sh"
            fi

            export LD=/usr/bin/clang
            export LD_FOR_TARGET=/usr/bin/clang

            echo "iOS development environment:"
            echo "DEVELOPER_DIR: $DEVELOPER_DIR"
            xcodebuild -version
          '';

          linuxHook = ''
            export LC_ALL=en_US.UTF-8
            export LANG=en_US.UTF-8
            export JAVA_HOME="${pkgs.jdk17.home}"
          '';

        in
        pkgs.mkShellNoCC {
          buildInputs = if system == "aarch64-darwin" then basePackages ++ darwinPackages else basePackages ++ [ pkgs.maestro ];

          shellHook = if system == "aarch64-darwin" then darwinHook else linuxHook;
        };

      mkServerShellFor =
        system:
        let
          pkgs = pkgsFor system;
        in
        pkgs.mkShell {
          buildInputs = with pkgs; [
            (rust-bin.stable."1.95.0".default.override {
              extensions = [
                "rust-src"
                "rust-analyzer"
              ];
            })
            cargo
            clippy
            pkg-config
            openssl
          ];
        };
    in
    {
      devShells = forAllSystems (system: {
        default = mkShellFor system;
        server = mkServerShellFor system;
      });
    };
}
