#!/bin/bash

set -e

install_package() {
    local package=$1
    if ! command -v "$package" &> /dev/null; then
        echo "$package not found. Installing..."
        sudo apt update
        sudo apt install -y "$package"
        echo "$package installed."
    else
        echo "$package is already installed."
    fi
}

install_go() {
    if ! command -v go &> /dev/null; then
        echo "Go not found. Installing..."
        GO_VERSION=$(curl -s "https://go.dev/VERSION?m=text" | head -n 1)
        GO_TAR="${GO_VERSION}.linux-amd64.tar.gz"
        GO_URL="https://go.dev/dl/$GO_TAR"
        sudo curl -fsSL "$GO_URL" -o /tmp/"$GO_TAR"
        sudo rm -rf /usr/local/go
        sudo tar -C /usr/local -xzf /tmp/"$GO_TAR"
        rm /tmp/"$GO_TAR"
        echo "export PATH=\$PATH:/usr/local/go/bin" | sudo tee /etc/profile.d/go_path.sh
        echo "Go installed successfully. You may need to restart your shell or source /etc/profile.d/go_path.sh to use the go command."
    else
        echo "Go is already installed."
    fi
}

install_caddy() {
    if ! command -v caddy &> /dev/null; then
        echo "Caddy not found. Installing..."
        sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
        sudo curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
        sudo curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
        sudo apt update
        sudo apt install -y caddy
        echo "Caddy installed successfully."
    else
        echo "Caddy is already installed."
    fi
}

configure_caddy_file() {
    local caddyfile="Caddyfile"
    if [ ! -f "$caddyfile" ]; then
        echo "$caddyfile not found in current directory. Skipping Caddy configuration."
        return 1
    fi
    echo "Copying $caddyfile to /etc/caddy/ and restarting Caddy."
    sudo mkdir -p /etc/caddy
    sudo cp "$caddyfile" /etc/caddy/
    sudo systemctl restart caddy
    sudo systemctl reload caddy
    echo "Caddy service reloaded with new Caddyfile."
}

echo "Starting server setup for Go and Caddy..."

install_go

install_caddy

install_package ufw

echo "Configuring firewall for Caddy..."
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow ssh
sudo ufw allow http
sudo ufw allow https
sudo ufw --force enable
echo "Firewall configured."
sudo ufw status verbose

echo "Setting up and starting systemd services..."

declare -a services=("goland")

for service_name in "${services[@]}"; do
    service_file="${service_name}-server.service"
    
    if [ ! -f "$service_file" ]; then
        echo "Service file $service_file not found. Skipping."
        continue
    fi
    
    log_dir="/var/log/${service_name}"
    mkdir -p "$log_dir"
    touch "$log_dir/out.log"
    touch "$log_dir/err.log"
    
    sudo cp "$service_file" "/etc/systemd/system/${service_name}.service"
    sudo systemctl daemon-reload
    sudo systemctl enable "${service_name}"
    sudo systemctl start "${service_name}"
    
    echo "Service ${service_name} configured and started."
done

echo "Configuring Caddy..."
configure_caddy_file

echo "Server setup completed successfully!"