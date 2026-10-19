# Stockroom Inventory

A lightweight inventory dashboard with a Java HTTP server and an HTML, CSS, and JavaScript frontend. It includes a demo login, six sample products, stock summary cards, product search, and a form for adding products.

## Requirements

- Java 17 or newer

## Run

From the project folder in PowerShell:

```powershell
New-Item -ItemType Directory -Force out
javac -d out src\InventoryServer.java
java -cp out InventoryServer
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080) in your browser.

Demo credentials: `admin` / `inventory123`

Products are held in memory and reset to the sample data when the server restarts. This demo login is intended for local use, not production deployment.
