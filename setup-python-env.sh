#!/usr/bin/env bash

# Determine if the script is sourced or executed directly
is_sourced() {
    [[ "${BASH_SOURCE[0]}" != "${0}" ]]
}

# Only enable strict mode when executed as a standalone script to prevent
# polluting the caller's interactive shell session with 'set -e'.
if ! is_sourced; then
    set -e
fi

# Determine directory of this script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${SCRIPT_DIR}/.venv"

# Ensure python3 is available
if ! command -v python3 &>/dev/null; then
    echo "Error: python3 is not available in PATH." >&2
    return 1 2>/dev/null || exit 1
fi

# Check for --update or --reinstall flags
UPDATE_DEPS=false
for arg in "$@"; do
    if [[ "$arg" == "--update" || "$arg" == "--reinstall" || "$arg" == "-u" ]]; then
        UPDATE_DEPS=true
    fi
done

NEEDS_INSTALL=false

# Create virtual environment if it does not already exist
if [ ! -d "${VENV_DIR}" ] || [ ! -f "${VENV_DIR}/bin/activate" ]; then
    echo "Creating virtual environment at ${VENV_DIR}..."
    python3 -m venv "${VENV_DIR}" || { return 1 2>/dev/null || exit 1; }
    NEEDS_INSTALL=true
else
    echo "Virtual environment already exists at ${VENV_DIR}."
fi

# Check if dependencies are already installed
if [ "${NEEDS_INSTALL}" = false ] && [ "${UPDATE_DEPS}" = false ]; then
    if ! "${VENV_DIR}/bin/python" -c "import faker" &>/dev/null; then
        NEEDS_INSTALL=true
    fi
fi

# Upgrade pip and install dependencies only if needed or explicitly requested
if [ "${NEEDS_INSTALL}" = true ] || [ "${UPDATE_DEPS}" = true ]; then
    echo "Upgrading pip..."
    "${VENV_DIR}/bin/python" -m pip install --upgrade pip

    if [ -f "${SCRIPT_DIR}/requirements.txt" ]; then
        echo "Installing dependencies from requirements.txt..."
        "${VENV_DIR}/bin/pip" install -r "${SCRIPT_DIR}/requirements.txt"
    else
        echo "requirements.txt not found; installing faker directly..."
        "${VENV_DIR}/bin/pip" install faker
    fi
else
    echo "Dependencies are already satisfied. (Pass --update to re-check PyPI)"
fi

# Activate the virtual environment
if is_sourced; then
    # Script is sourced: activate in the current shell session
    # shellcheck disable=SC1090
    source "${VENV_DIR}/bin/activate"
    echo "Virtual environment activated: $(which python)"
else
    # Script is executed directly: provide activation command to user
    echo ""
    echo "Virtual environment setup complete."
    echo "To activate this environment in your current shell, run:"
    echo "    source .venv/bin/activate"
fi
