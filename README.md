🔐IoT Based Smart storage Locker System (IoT + Android + Payment Integration)

A complete Smart Locker System that allows users to reserve lockers, make payments, receive OTPs, and unlock lockers using an Android app + Node.js backend + ESP32 hardware.

🚀 Project Overview

This system provides a secure and automated way to manage lockers using:

📱 Android Application (Jetpack Compose)
🌐 Node.js Backend (Express + Razorpay)
🔌 ESP32 Hardware (WiFi + Relay Control)

Users can:

View locker availability
Reserve a locker
Pay online via Razorpay
Receive OTP
Unlock locker using OTP
Perform partial/full pickup

🧠 System Architecture
Android App ⇄ HTTP ⇄ Node.js Backend ⇄ ESP32 ⇄ Locker Hardware
Android communicates with backend via REST APIs
Backend handles logic, OTP, and payments
ESP32 verifies OTP and controls physical locks

🔄 Complete Flow
User opens app → sees available lockers
User selects locker → /reserve API
Payment initiated via Razorpay
On success → /payment-success
OTP generated and shown to user
User enters OTP (via ESP32 / system)
ESP32 verifies OTP → /verify-otp
Locker unlocks for 3 minutes
Auto reset → locker becomes AVAILABLE

🔐 Locker State Machine
AVAILABLE → RESERVED → WAITING_OTP → OCCUPIED → AVAILABLE
🧩 Features
✅ User Features
Real-time locker availability
Secure OTP-based access
Razorpay payment integration
Partial / Full pickup options
Live countdown timer

⚙️ System Features
Auto-release if payment not completed (2 min)
Auto-reset after usage (3 min)
In-memory locker state management
REST API based architecture

🖥️ Backend (Node.js)
📦 Tech Stack
Node.js
Express
Razorpay API
🔑 APIs
Method	Endpoint	Description
GET	/lockers	Get locker status
POST	/reserve	Reserve locker
POST	/create-order	Create Razorpay order
POST	/payment-success	Generate OTP
POST	/request-otp	Regenerate OTP
POST	/verify-otp	Verify OTP & unlock

▶️ Run Backend
npm install
node index.js

Server runs on:

http://localhost:3000


📱 Android App
📦 Tech Stack
Kotlin
Jetpack Compose
Razorpay SDK
OkHttp
📲 Features
Locker dashboard UI
Live status refresh (every 3 sec)
Payment integration
OTP display
Pickup options

▶️ Setup
Open in Android Studio
Replace backend IP:
http://192.168.X.X:3000
Run on device/emulator

🔌 ESP32 (Hardware)
⚙️ Components
ESP32
4-Channel Relay Module
Solenoid Locks
🔌 Pin Mapping
Locker	GPIO
L1	5
L2	18
L3	19
L4	21

▶️ Flow
Receives input via Serial:
L1-1234      → OTP verification
L1-PARTIAL   → Partial pickup
L1-FULL      → Full pickup
Sends request to backend:
/verify-otp
Unlocks locker if OTP is valid

🔧 Future Improvements
✅ Secure Razorpay webhook verification
✅ Add user authentication
✅ Deploy to cloud

🧪 Test Payment Details (Razorpay)
Card Number: 4111 1111 1111 1111
Expiry: Any future date
CVV: Any 3 digits

📂 Project Structure
smart-locker/
│
├── backend/        # Node.js server
├── android-app/    # Android project
├── esp32/          # ESP32 code
└── README.md


🧑‍💻 Author
Swaroop Patil

📜 License

This project is for academic / educational use.
