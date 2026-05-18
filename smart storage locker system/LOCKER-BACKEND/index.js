// ===== ONE‑TIME FLAG – SET TO true FOR THE VERY FIRST RUN, THEN false FOREVER =====
const FORCE_RESET = false;   // <-- change to false after the first successful startup
// =================================================================================

const express = require("express");
const cors = require("cors");
const Razorpay = require("razorpay");
const mqtt = require("mqtt");
const { Sequelize, DataTypes } = require("sequelize");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");

const app = express();
app.use(cors());
app.use(express.json());

// =====================
// 🎨 COLORED LOGGING
// =====================
const colors = {
  reset: '\x1b[0m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  red: '\x1b[31m',
  cyan: '\x1b[36m',
  gray: '\x1b[90m'
};

function logInfo(msg)  { console.log(`${colors.cyan}[${new Date().toLocaleTimeString()}]${colors.reset} ${colors.green}✓${colors.reset} ${msg}`); }
function logWarn(msg)  { console.log(`${colors.cyan}[${new Date().toLocaleTimeString()}]${colors.reset} ${colors.yellow}⚠${colors.reset} ${msg}`); }
function logError(msg) { console.log(`${colors.cyan}[${new Date().toLocaleTimeString()}]${colors.reset} ${colors.red}✗${colors.reset} ${msg}`); }
function logDebug(msg) { console.log(`${colors.cyan}[${new Date().toLocaleTimeString()}]${colors.reset} ${colors.gray}🔍${colors.reset} ${msg}`); }

// =====================
// 🔐 JWT SECRET
// =====================
const JWT_SECRET = "YOUR JWT CODE ";

// =====================
// 🗄️ SQLITE DATABASE
// =====================
const sequelize = new Sequelize({
  dialect: "sqlite",
  storage: "./database.sqlite",
  logging: false
});

// Models
const User = sequelize.define("User", {
  id: { type: DataTypes.UUID, defaultValue: DataTypes.UUIDV4, primaryKey: true },
  name: { type: DataTypes.STRING, allowNull: false },
  email: { type: DataTypes.STRING, allowNull: false, unique: true },
  password: { type: DataTypes.STRING, allowNull: false }
}, { tableName: "users", timestamps: true, createdAt: "created_at", updatedAt: false });

const Admin = sequelize.define("Admin", {
  id: { type: DataTypes.UUID, defaultValue: DataTypes.UUIDV4, primaryKey: true },
  username: { type: DataTypes.STRING, allowNull: false, unique: true },
  password: { type: DataTypes.STRING, allowNull: false }
}, { tableName: "admins", timestamps: true, createdAt: "created_at", updatedAt: false });

const Locker = sequelize.define("Locker", {
  id: { type: DataTypes.UUID, defaultValue: DataTypes.UUIDV4, primaryKey: true },
  lockerId: { type: DataTypes.STRING, allowNull: false },
  locationId: { type: DataTypes.STRING, allowNull: false },
  status: { type: DataTypes.STRING, defaultValue: "AVAILABLE" },
  otp: { type: DataTypes.STRING, allowNull: true },
  sessionStart: { type: DataTypes.BIGINT, allowNull: true },
  duration: { type: DataTypes.INTEGER, defaultValue: 0 },
  amount: { type: DataTypes.INTEGER, defaultValue: 0 },
  userId: { type: DataTypes.UUID, allowNull: true },
  pendingFullReset: { type: DataTypes.BOOLEAN, defaultValue: false },
  penaltyOTP: { type: DataTypes.BOOLEAN, defaultValue: false }
}, {
  tableName: "lockers",
  timestamps: false,
  indexes: [{ unique: true, fields: ['lockerId', 'locationId'] }]
});

// =====================
// 🔑 RAZORPAY CONFIG
// =====================
const razorpay = new Razorpay({
  key_id: "YOUR RAZORPAY API KEY",
  key_secret: "YOUR RAZORPAY SECRET KEY"   // ⚠️ REPLACE WITH YOUR ACTUAL SECRET
});

// =====================
// 📡 MQTT CONFIG
// =====================
const MQTT_BROKER = "mqtt://broker.hivemq.com";
const MQTT_TOPIC = "smartlocker/unlock";
const mqttClient = mqtt.connect(MQTT_BROKER);
mqttClient.on("connect", () => logInfo("MQTT connected"));
mqttClient.on("error", (err) => logError(`MQTT error: ${err}`));

// =====================
// 🏷️ STATUS CONSTANTS
// =====================
const STATUS = {
  AVAILABLE: "AVAILABLE",
  RESERVED: "RESERVED",
  WAITING_OTP: "WAITING_OTP",
  OCCUPIED: "OCCUPIED",
  PENALTY: "PENALTY"
};

// Active timers (in‑memory)
const activeTimers = new Map();

// =====================
// 📡 BROADCAST LOCKER UPDATE
// =====================
async function broadcastLockerUpdate(locationId) {
  const lockers = await Locker.findAll({ where: { locationId } });
  const safe = {};
  lockers.forEach(l => {
    safe[l.lockerId] = {
      status: l.status,
      sessionStart: l.sessionStart,
      otp: l.otp,
      userId: l.userId
    };
  });
  io.emit("lockers_update", safe);
  logDebug(`📡 Locker update broadcasted for location: ${locationId}`);
}

// =====================
// 🖨️ CONSOLE STATUS TABLE
// =====================
async function printLockerStatus() {
  const allLockers = await Locker.findAll();
  const locationMap = {};
  allLockers.forEach(l => {
    if (!locationMap[l.locationId]) locationMap[l.locationId] = [];
    locationMap[l.locationId].push(l);
  });

  for (const [locId, lockers] of Object.entries(locationMap)) {
    console.log('\n' + '='.repeat(80));
    console.log(`${colors.cyan}📋 Location: ${locId} – ${new Date().toLocaleTimeString()}${colors.reset}`);
    console.log('='.repeat(80));
    const tableData = lockers.map(l => {
      let timerInfo = '—';
      if (l.status === 'OCCUPIED' && l.sessionStart) {
        const elapsed = Math.floor((Date.now() - l.sessionStart) / 1000);
        timerInfo = `${Math.floor(elapsed/60)}:${(elapsed%60).toString().padStart(2,'0')} elapsed`;
      } else if (l.status === 'PENALTY') timerInfo = 'PENALTY';
      else if (l.status === 'WAITING_OTP') timerInfo = 'awaiting OTP';
      else if (l.status === 'RESERVED') timerInfo = 'reserved (2m)';
      return {
        Locker: l.lockerId,
        Status: l.status,
        OTP: l.otp || '—',
        Timer: timerInfo,
        User: l.userId ? l.userId.slice(-8) : '—'
      };
    });
    console.table(tableData);
    console.log('='.repeat(80) + '\n');
  }
}

// =====================
// ⌨️ TERMINAL COMMANDS
// =====================
process.stdin.setEncoding('utf8');
process.stdin.on('data', (input) => {
  const cmd = input.trim().toLowerCase();
  if (cmd === 'status') {
    printLockerStatus();
  } else if (cmd === 'help') {
    console.log('\n📌 Available commands:');
    console.log('   status  - Print current locker status for all locations');
    console.log('   help    - Show this help');
    console.log('   clear   - Clear console\n');
  } else if (cmd === 'clear') {
    console.clear();
  } else if (cmd !== '') {
    console.log(`❓ Unknown command: "${cmd}". Type "help" for available commands.`);
  }
});

// =====================
// 🔐 AUTH MIDDLEWARE
// =====================
function authenticateToken(req, res, next) {
  const authHeader = req.headers["authorization"];
  const token = authHeader && authHeader.split(" ")[1];
  if (!token) return res.status(401).json({ error: "Access denied" });
  jwt.verify(token, JWT_SECRET, (err, user) => {
    if (err) return res.status(403).json({ error: "Invalid token" });
    req.user = user;
    next();
  });
}

function authenticateAdmin(req, res, next) {
  const authHeader = req.headers["authorization"];
  const token = authHeader && authHeader.split(" ")[1];
  if (!token) return res.status(401).json({ error: "Access denied" });
  jwt.verify(token, JWT_SECRET, (err, decoded) => {
    if (err) return res.status(403).json({ error: "Invalid token" });
    if (decoded.role !== 'admin') return res.status(403).json({ error: "Admin access required" });
    req.admin = decoded;
    next();
  });
}

// =====================
// 🕒 TIMER HELPERS
// =====================
function clearLockerTimer(locationId, lockerId) {
  const key = `${locationId}:${lockerId}`;
  if (activeTimers.has(key)) {
    clearTimeout(activeTimers.get(key));
    activeTimers.delete(key);
  }
}

async function startOccupiedTimer(locationId, lockerId) {
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker) return;
  clearLockerTimer(locationId, lockerId);
  if (!locker.sessionStart) {
    locker.sessionStart = Date.now();
    await locker.save();
  }
  locker.status = STATUS.OCCUPIED;
  locker.penaltyOTP = false;
  await locker.save();

  const durationMs = (locker.duration || 3) * 60 * 1000;
  const timer = setTimeout(async () => {
    const freshLocker = await Locker.findOne({ where: { lockerId, locationId } });
    if (freshLocker && freshLocker.status === STATUS.OCCUPIED) {
      freshLocker.status = STATUS.PENALTY;
      await freshLocker.save();
      logWarn(`Locker ${locationId}/${lockerId} entered PENALTY`);
      printLockerStatus();
      broadcastLockerUpdate(locationId);
    }
    activeTimers.delete(`${locationId}:${lockerId}`);
  }, durationMs);
  activeTimers.set(`${locationId}:${lockerId}`, timer);
  logInfo(`Timer started for ${locationId}/${lockerId} (${locker.duration} min)`);
  printLockerStatus();
  broadcastLockerUpdate(locationId);
}

async function resetLocker(locationId, lockerId) {
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker) return;
  clearLockerTimer(locationId, lockerId);
  locker.status = STATUS.AVAILABLE;
  locker.otp = null;
  locker.sessionStart = null;
  locker.pendingFullReset = false;
  locker.penaltyOTP = false;
  locker.duration = 0;
  locker.amount = 0;
  locker.userId = null;
  await locker.save();
  logInfo(`Locker ${locationId}/${lockerId} reset to AVAILABLE`);
  printLockerStatus();
  broadcastLockerUpdate(locationId);
}

// =====================
// 📡 REQUEST LOGGER
// =====================
app.use((req, res, next) => {
  if (req.method !== "GET") {
    logDebug(`${req.method} ${req.url} – ${JSON.stringify(req.body)}`);
  }
  next();
});

// =====================
// 👤 AUTH ROUTES
// =====================
app.post("/api/auth/signup", async (req, res) => {
  try {
    const { name, email, password } = req.body;
    if (!name || !email || !password) return res.status(400).json({ error: "All fields required" });
    const existing = await User.findOne({ where: { email } });
    if (existing) return res.status(400).json({ error: "Email already registered" });
    const hashed = await bcrypt.hash(password, 10);
    const user = await User.create({ name, email, password: hashed });
    const token = jwt.sign({ id: user.id, email: user.email, name: user.name, role: 'user' }, JWT_SECRET);
    logInfo(`New user signed up: ${email}`);
    res.json({ token, user: { id: user.id, name: user.name, email: user.email } });
  } catch (err) { logError(`Signup error: ${err.message}`); res.status(500).json({ error: "Server error" }); }
});

app.post("/api/auth/login", async (req, res) => {
  try {
    const { email, password } = req.body;
    const user = await User.findOne({ where: { email } });
    if (!user) return res.status(401).json({ error: "Invalid credentials" });
    const valid = await bcrypt.compare(password, user.password);
    if (!valid) return res.status(401).json({ error: "Invalid credentials" });
    const token = jwt.sign({ id: user.id, email: user.email, name: user.name, role: 'user' }, JWT_SECRET);
    logInfo(`User logged in: ${email}`);
    res.json({ token, user: { id: user.id, name: user.name, email: user.email } });
  } catch (err) { logError(`Login error: ${err.message}`); res.status(500).json({ error: "Server error" }); }
});

app.post("/api/admin/login", async (req, res) => {
  try {
    const { username, password } = req.body;
    if (!username || !password) return res.status(400).json({ error: "Username and password required" });
    const admin = await Admin.findOne({ where: { username } });
    if (!admin) return res.status(401).json({ error: "Invalid credentials" });
    const valid = await bcrypt.compare(password, admin.password);
    if (!valid) return res.status(401).json({ error: "Invalid credentials" });
    const token = jwt.sign({ id: admin.id, username: admin.username, role: 'admin' }, JWT_SECRET);
    logInfo(`Admin logged in: ${username}`);
    res.json({ token, admin: { id: admin.id, username: admin.username } });
  } catch (err) { logError(`Admin login error: ${err.message}`); res.status(500).json({ error: "Server error" }); }
});

// =====================
// 🌍 GET LOCATIONS LIST
// =====================
app.get("/locations", (req, res) => {
  const list = [
    { id: "metro", name: "Metro Station" },
    { id: "mall", name: "Shopping Mall" },
    { id: "railway", name: "Railway Station" },
    { id: "airport", name: "Airport" },
    { id: "cinema", name: "Cinema Hall" }
  ];
  res.json(list);
});

// =====================
// 📋 GET LOCKERS FOR A LOCATION
// =====================
app.get("/lockers/:locationId", async (req, res) => {
  const { locationId } = req.params;
  const lockers = await Locker.findAll({ where: { locationId } });
  const safe = {};
  lockers.forEach(l => {
    safe[l.lockerId] = {
      status: l.status,
      sessionStart: l.sessionStart,
      otp: l.otp,
      userId: l.userId
    };
  });
  res.json(safe);
});

// =====================
// 🖥️ ADMIN DASHBOARD (read‑only)
// =====================
app.get("/admin", (req, res) => {
  const locOptions = ["metro","mall","railway","airport","cinema"]
    .map(id => `<option value="${id}">${id}</option>`).join('');
  const html = `<!DOCTYPE html>
<html>
<head>
  <title>Smart Locker Admin</title>
  <meta charset="UTF-8">
  <style>
    body { font-family: Arial, sans-serif; background: #0F172A; color: #E2E8F0; padding: 20px; }
    h1 { color: #60A5FA; }
    select { padding: 8px; border-radius: 6px; border: none; margin-bottom: 16px; }
    table { width: 100%; border-collapse: collapse; background: #1E293B; border-radius: 12px; overflow: hidden; }
    th { background: #3B82F6; color: white; padding: 12px; text-align: left; }
    td { padding: 12px; border-bottom: 1px solid #334155; }
    .status { font-weight: bold; padding: 4px 8px; border-radius: 20px; display: inline-block; }
    .AVAILABLE { background: #22C55E; color: white; }
    .RESERVED { background: #6B7280; color: white; }
    .WAITING_OTP { background: #3B82F6; color: white; }
    .OCCUPIED { background: #F59E0B; color: white; }
    .PENALTY { background: #EF4444; color: white; }
    .otp { font-family: monospace; font-size: 1.2em; background: #0F172A; padding: 4px 8px; border-radius: 6px; }
    .timer { font-family: monospace; }
    .footer { margin-top: 20px; color: #64748B; text-align: center; }
  </style>
</head>
<body>
  <h1>🔒 Smart Locker Admin Dashboard</h1>
  <label for="locSelect">Location:</label>
  <select id="locSelect" onchange="loadLockers()">${locOptions}</select>
  <p>Live updates every second</p>
  <table>
    <thead><tr><th>Locker</th><th>Status</th><th>OTP</th><th>Time</th><th>User ID</th></tr></thead>
    <tbody id="tableBody"><tr><td colspan="5">Loading...</td></tr></tbody>
  </table>
  <div class="footer">⚡ Live updates (no page reload) | Smart Locker Backend v1.0</div>

  <script>
    async function loadLockers() {
      var loc = document.getElementById('locSelect').value;
      var res = await fetch('/lockers/' + loc);
      var lockers = await res.json();
      var tbody = document.getElementById('tableBody');
      var html = '';
      var now = Date.now();
      var keys = Object.keys(lockers);
      for (var i = 0; i < keys.length; i++) {
        var id = keys[i];
        var l = lockers[id];
        var time = '—';
        if (l.status === 'OCCUPIED' && l.sessionStart) {
          var elapsed = Math.floor((now - l.sessionStart) / 1000);
          var mins = Math.floor(elapsed / 60);
          var secs = String(elapsed % 60).padStart(2, '0');
          time = mins + ':' + secs + ' elapsed';
        } else if (l.status === 'PENALTY') time = 'Penalty';
        else if (l.status === 'WAITING_OTP') time = 'Awaiting OTP';
        else if (l.status === 'RESERVED') time = 'Reserved (2m)';
        html += '<tr>' +
          '<td><strong>' + id + '</strong></td>' +
          '<td><span class="status ' + l.status + '">' + l.status + '</span></td>' +
          '<td>' + (l.otp ? '<span class="otp">' + l.otp + '</span>' : '—') + '</td>' +
          '<td>' + time + '</td>' +
          '<td>' + (l.userId ? l.userId.slice(0,8) + '…' : '—') + '</td>' +
        '</tr>';
      }
      tbody.innerHTML = html;
    }
    loadLockers();
    setInterval(loadLockers, 1000);
  </script>
</body>
</html>`;
  res.send(html);
});

// =====================
// 🔒 RESERVE LOCKER
// =====================
app.post("/reserve", authenticateToken, async (req, res) => {
  const { locationId, lockerId, duration, amount } = req.body;
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker || locker.status !== STATUS.AVAILABLE) {
    return res.status(400).json({ error: "Not available" });
  }

  locker.status = STATUS.RESERVED;
  locker.duration = duration || 3;
  locker.amount = amount || 5000;
  locker.userId = req.user.id;
  await locker.save();

  clearLockerTimer(locationId, lockerId);
  const timer = setTimeout(async () => {
    const fresh = await Locker.findOne({ where: { lockerId, locationId } });
    if (fresh && fresh.status === STATUS.RESERVED) {
      await resetLocker(locationId, lockerId);
      logWarn(`Reservation expired for ${locationId}/${lockerId}`);
    }
    activeTimers.delete(`${locationId}:${lockerId}`);
  }, 120000);
  activeTimers.set(`${locationId}:${lockerId}`, timer);

  logInfo(`${locationId}/${lockerId} RESERVED by ${req.user.email}`);
  await printLockerStatus();
  broadcastLockerUpdate(locationId);
  res.json({ success: true });
});

// =====================
// 💳 CREATE RAZORPAY ORDER
// =====================
app.post("/create-order", authenticateToken, async (req, res) => {
  try {
    const { locationId, lockerId, amount } = req.body;
    const locker = await Locker.findOne({ where: { lockerId, locationId } });
    if (!locker || locker.status !== STATUS.RESERVED || locker.userId !== req.user.id) {
      return res.status(400).json({ error: "Invalid state" });
    }
    const orderAmount = amount || locker.amount || 5000;
    const order = await razorpay.orders.create({ amount: orderAmount, currency: "INR" });
    logInfo(`Order created for ${locationId}/${lockerId}: ₹${orderAmount/100}`);
    res.json(order);
  } catch (err) { logError(`Order error: ${err.message}`); res.status(500).json({ error: "Order failed" }); }
});

// =====================
// ✅ PAYMENT SUCCESS
// =====================
app.post("/payment-success", authenticateToken, async (req, res) => {
  const { locationId, lockerId } = req.body;
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker || locker.status !== STATUS.RESERVED || locker.userId !== req.user.id) {
    return res.status(400).json({ error: "Invalid state" });
  }

  const otp = Math.floor(1000 + Math.random() * 9000).toString();
  locker.otp = otp;
  locker.status = STATUS.WAITING_OTP;
  await locker.save();
  clearLockerTimer(locationId, lockerId);

  logInfo(`Payment success for ${locationId}/${lockerId}, OTP: ${otp}`);
  printLockerStatus();
  broadcastLockerUpdate(locationId);
  res.json({ success: true, otp });
});

// =====================
// 🔑 VERIFY OTP FROM APP
// =====================
app.post("/verify-otp-app", authenticateToken, async (req, res) => {
  const { locationId, lockerId, otp } = req.body;
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker || locker.userId !== req.user.id) {
    return res.status(400).json({ error: "Invalid" });
  }

  const validStates = [STATUS.WAITING_OTP, STATUS.PENALTY];
  if (!validStates.includes(locker.status) || locker.otp !== otp) {
    logWarn(`Invalid OTP attempt for ${locationId}/${lockerId}: ${otp}`);
    return res.json({ success: false });
  }

  locker.otp = null;
  const isPenaltyOTP = locker.penaltyOTP === true;
  const isFullReset = locker.pendingFullReset || isPenaltyOTP;
  await locker.save();

  mqttClient.publish(MQTT_TOPIC, JSON.stringify({ lockerId, action: "UNLOCK" }));
  logInfo(`UNLOCK command sent for ${locationId}/${lockerId}`);

  if (isFullReset) await resetLocker(locationId, lockerId);
  else await startOccupiedTimer(locationId, lockerId);

  res.json({ success: true });
});

// =====================
// 📦 PICKUP REQUEST
// =====================
app.post("/pickup", authenticateToken, async (req, res) => {
  const { locationId, lockerId, type } = req.body;
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker || locker.status !== STATUS.OCCUPIED || locker.userId !== req.user.id) {
    return res.status(400).json({ error: "Invalid" });
  }

  const otp = Math.floor(1000 + Math.random() * 9000).toString();
  locker.otp = otp;
  locker.status = STATUS.WAITING_OTP;
  locker.pendingFullReset = (type === "full");
  await locker.save();

  logInfo(`${type.toUpperCase()} pickup for ${locationId}/${lockerId}, OTP: ${otp}`);
  printLockerStatus();
  broadcastLockerUpdate(locationId);
  res.json({ success: true, otp });
});

// =====================
// 💰 PENALTY PAYMENT
// =====================
app.post("/pay-penalty", authenticateToken, async (req, res) => {
  const { locationId, lockerId } = req.body;
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker || locker.status !== STATUS.PENALTY || locker.userId !== req.user.id) {
    return res.status(400).json({ error: "Invalid" });
  }
  try {
    const order = await razorpay.orders.create({ amount: 20000, currency: "INR" });
    logInfo(`Penalty order created for ${locationId}/${lockerId}`);
    res.json(order);
  } catch (err) { res.status(500).json({ error: "Order failed" }); }
});

app.post("/penalty-success", authenticateToken, async (req, res) => {
  const { locationId, lockerId } = req.body;
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker || locker.status !== STATUS.PENALTY || locker.userId !== req.user.id) {
    return res.status(400).json({ error: "Invalid" });
  }
  const otp = Math.floor(1000 + Math.random() * 9000).toString();
  locker.otp = otp;
  locker.status = STATUS.WAITING_OTP;
  locker.penaltyOTP = true;
  await locker.save();
  logInfo(`Penalty paid for ${locationId}/${lockerId}, OTP: ${otp}`);
  printLockerStatus();
  broadcastLockerUpdate(locationId);
  res.json({ success: true, otp });
});

// =====================
// 🛡️ ADMIN FORCE UNLOCK / RESET
// =====================
app.post("/admin/force-unlock", authenticateAdmin, async (req, res) => {
  const { locationId, lockerId } = req.body;
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker) return res.status(404).json({ error: "Locker not found" });

  mqttClient.publish(MQTT_TOPIC, JSON.stringify({ lockerId, action: "UNLOCK" }));
  logWarn(`ADMIN (${req.admin.username}): Force unlock ${locationId}/${lockerId}`);
  res.json({ success: true });
});

app.post("/admin/force-reset", authenticateAdmin, async (req, res) => {
  const { locationId, lockerId } = req.body;
  const locker = await Locker.findOne({ where: { lockerId, locationId } });
  if (!locker) return res.status(404).json({ error: "Locker not found" });

  await resetLocker(locationId, lockerId);
  logWarn(`ADMIN (${req.admin.username}): Force reset ${locationId}/${lockerId} to AVAILABLE`);
  res.json({ success: true });
});

// =====================
// 🔌 SOCKET.IO
// =====================
const http = require("http");
const socketIo = require("socket.io");
const server = http.createServer(app);
const io = socketIo(server, { cors: { origin: "*", methods: ["GET", "POST"] } });

io.on("connection", (socket) => {
  logInfo(`Socket connected: ${socket.id}`);
  socket.on("join_location", async (locationId) => {
    socket.join(locationId);
    const lockers = await Locker.findAll({ where: { locationId } });
    const safe = {};
    lockers.forEach(l => { safe[l.lockerId] = { status: l.status, sessionStart: l.sessionStart, otp: l.otp, userId: l.userId }; });
    socket.emit("lockers_update", safe);
  });
});

// =====================
// 🚀 START SERVER WITH ONE‑TIME FORCE RESET
// =====================
async function restoreActiveTimers() {
  try {
    const activeLockers = await Locker.findAll({
      where: { status: [STATUS.RESERVED, STATUS.OCCUPIED] }
    });
    for (const locker of activeLockers) {
      if (locker.status === STATUS.RESERVED) {
        const timer = setTimeout(async () => {
          const fresh = await Locker.findOne({ where: { lockerId: locker.lockerId, locationId: locker.locationId } });
          if (fresh && fresh.status === STATUS.RESERVED) {
            await resetLocker(locker.locationId, locker.lockerId);
          }
        }, 120000);
        activeTimers.set(`${locker.locationId}:${locker.lockerId}`, timer);
      } else if (locker.status === STATUS.OCCUPIED && locker.sessionStart) {
        const elapsed = Date.now() - locker.sessionStart;
        const totalDuration = (locker.duration || 3) * 60 * 1000;
        const remaining = totalDuration - elapsed;
        if (remaining > 0) {
          const timer = setTimeout(async () => {
            const fresh = await Locker.findOne({ where: { lockerId: locker.lockerId, locationId: locker.locationId } });
            if (fresh && fresh.status === STATUS.OCCUPIED) {
              fresh.status = STATUS.PENALTY;
              await fresh.save();
              logWarn(`Locker ${locker.locationId}/${locker.lockerId} entered PENALTY (restored timer)`);
              broadcastLockerUpdate(locker.locationId);
            }
            activeTimers.delete(`${locker.locationId}:${locker.lockerId}`);
          }, remaining);
          activeTimers.set(`${locker.locationId}:${locker.lockerId}`, timer);
          logInfo(`Restored occupied timer for ${locker.locationId}/${locker.lockerId} (${Math.ceil(remaining/60000)} min left)`);
        } else {
          locker.status = STATUS.PENALTY;
          await locker.save();
          logWarn(`Locker ${locker.locationId}/${locker.lockerId} exceeded occupancy during restart – moved to PENALTY`);
        }
      }
    }
  } catch (err) {
    logError(`Timer restoration failed: ${err.message}`);
  }
}

async function seedDefaultData() {
  const existingAdmin = await Admin.findOne({ where: { username: "admin" } });
  if (!existingAdmin) {
    const hashed = await bcrypt.hash("admin123", 10);
    await Admin.create({ username: "admin", password: hashed });
    logInfo("Default admin created (admin / admin123)");
  }

  const locIds = ["metro", "mall", "railway", "airport", "cinema"];
  for (const locId of locIds) {
    for (let i = 1; i <= 4; i++) {
      const lockerId = `L${i}`;
      await Locker.findOrCreate({
        where: { lockerId, locationId: locId },
        defaults: { lockerId, locationId: locId, status: STATUS.AVAILABLE }
      });
    }
  }
}

async function start() {
  try {
    await sequelize.authenticate();
    logInfo("Database connected");

    // Use the FORCE_RESET flag
    const syncMode = { force: FORCE_RESET };
    await User.sync(syncMode);
    await Admin.sync(syncMode);
    await Locker.sync(syncMode);

    if (FORCE_RESET) {
      logWarn("⚠️ Running with FORCE_RESET = true. All tables were dropped and recreated.");
      logWarn("⚠️ Please set FORCE_RESET to false in the code before the next restart!");
    }

    await seedDefaultData();
    await restoreActiveTimers();

    server.listen(3000, () => {
      console.log(`\n${colors.green}🚀 Server running on http://localhost:3000${colors.reset}`);
      console.log(`${colors.cyan}📊 Admin Dashboard: http://localhost:3000/admin${colors.reset}`);
      console.log(`${colors.cyan}📌 Type "status" in this terminal to view locker state${colors.reset}\n`);
      printLockerStatus();
    });
  } catch (err) {
    logError(`Startup error: ${err.message}`);
    if (err.name === 'SequelizeValidationError') {
      err.errors.forEach(e => console.error(`   → ${e.path}: ${e.message}`));
    }
    process.exit(1);
  }
}
start();