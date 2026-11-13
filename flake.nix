{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37.0" ];
        buildToolsVersions = [ "37.0.0" ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      };
      androidSdk = androidComposition.androidsdk;
      aapt2Path = "${androidSdk}/libexec/android-sdk/build-tools/37.0.0/aapt2";
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          androidSdk
          android-tools
          jdk21
          gradle
        ];

        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        JAVA_HOME = "${pkgs.jdk21.home}";
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Path}";
      };
    };
}
