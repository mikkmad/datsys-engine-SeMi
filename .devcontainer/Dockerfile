# Gets the Ubuntu Resolute base image from Microsoft Container Registry (MCR). This is a lightweight image that is optimized for development containers. It includes essential tools and libraries needed for development, while keeping the image size small (see: https://github.com/devcontainers/images/tree/main/src/base-ubuntu). See a list of available base images here: https://hub.docker.com/r/microsoft/devcontainers
# - Copilot.
FROM mcr.microsoft.com/devcontainers/base:resolute

# As I am running this container on a Fedora Atomic Host using Rootless Podman for containers, I needed to precreate the `/tmp/.X11-unix` directory and set the permissions to `1777`, before the container starts. Otherwise, I was getting a "permission denied" error when the container - as a non-root - was trying to create and access these folders. This is what the below command does.
# Why permission `1777` is the chosen one, is documented here: https://access.redhat.com/solutions/49267
# - Mikkel
RUN mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix