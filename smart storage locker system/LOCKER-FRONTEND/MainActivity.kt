package com.example.smartlocker

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*

// -------------------- Data Classes --------------------
data class LockerState(val status: String, val sessionStart: Long? = null)
data class RateOption(val durationMinutes: Int, val price: Int, val label: String)
data class UserInfo(val id: String, val name: String, val email: String)
data class LocationInfo(val id: String, val name: String)

class MainActivity : ComponentActivity(), PaymentResultListener {

    private lateinit var client: OkHttpClient
    private val BASE_URL = "http://10.77.179.139:3000"   // ⚠️ CHANGE TO YOUR SERVER IP

    private var currentLocker = ""
    private var currentLocationId = ""
    private var pendingPenaltyPayment = false
    private val rateOptions = listOf(
        RateOption(2, 10, "2 minutes"), RateOption(3, 20, "3 minutes"),
        RateOption(60, 40, "1 hour"), RateOption(180, 80, "3 hours"),
        RateOption(360, 120, "6 hours"), RateOption(720, 240, "12 hours"),
        RateOption(1440, 480, "24 hours")
    )

    private lateinit var prefs: SharedPreferences
    private var authToken: String? = null
    private var currentUser: UserInfo? = null
    private var selectedLocation: LocationInfo? = null
    private val _otpDialogTrigger = mutableStateOf<Pair<String, String>?>(null)
    private var adminToken: String? = null
    private var isAdminMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Checkout.preload(applicationContext)

        prefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        authToken = prefs.getString("token", null)
        adminToken = prefs.getString("admin_token", null)
        val userJson = prefs.getString("user", null)
        if (userJson != null) {
            val obj = JSONObject(userJson)
            currentUser = UserInfo(obj.getString("id"), obj.getString("name"), obj.getString("email"))
        }
        val locJson = prefs.getString("location", null)
        if (locJson != null) {
            val obj = JSONObject(locJson)
            selectedLocation = LocationInfo(obj.getString("id"), obj.getString("name"))
        }
        client = OkHttpClient()

        setContent {
            var isLoggedIn by remember { mutableStateOf(!authToken.isNullOrEmpty()) }
            var adminLoggedIn by remember { mutableStateOf(!adminToken.isNullOrEmpty()) }
            var locationChosen by remember { mutableStateOf(selectedLocation != null) }

            if (adminLoggedIn) {
                AdminScreen(
                    onLogout = {
                        prefs.edit().remove("admin_token").apply()
                        adminToken = null
                        adminLoggedIn = false
                        isAdminMode = false
                    }
                )
            } else if (!isLoggedIn && !adminLoggedIn) {
                AuthScreen(
                    onUserLoginSuccess = { token, user ->
                        prefs.edit().putString("token", token).putString("user", JSONObject().apply {
                            put("id", user.id); put("name", user.name); put("email", user.email)
                        }.toString()).apply()
                        authToken = token; currentUser = user; isLoggedIn = true
                    },
                    onAdminLoginSuccess = { token ->
                        prefs.edit().putString("admin_token", token).apply()
                        adminToken = token; adminLoggedIn = true; isAdminMode = true
                    }
                )
            } else if (isLoggedIn && !locationChosen) {
                LocationSelectionScreen(
                    onLocationSelected = { loc ->
                        selectedLocation = loc
                        prefs.edit().putString("location", JSONObject().apply { put("id", loc.id); put("name", loc.name) }.toString()).apply()
                        locationChosen = true
                    }
                )
            } else {
                MainAppScreen(
                    location = selectedLocation!!,
                    onLogout = {
                        prefs.edit().clear().apply()
                        authToken = null; currentUser = null; selectedLocation = null
                        isLoggedIn = false; locationChosen = false
                    }
                )
            }
        }
    }

    // ----------------------------------------------------------------
    //  AUTH SCREEN – no `return` statements anywhere
    // ----------------------------------------------------------------
    @Composable
    fun AuthScreen(
        onUserLoginSuccess: (String, UserInfo) -> Unit,
        onAdminLoginSuccess: (String) -> Unit
    ) {
        var isLogin by remember { mutableStateOf(true) }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isAdmin by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val performAuth: () -> Unit = {
            val hasError = email.isBlank() || password.isBlank() || (!isLogin && !isAdmin && name.isBlank())
            if (hasError) {
                errorMessage = "Please fill all fields"
            } else {
                errorMessage = null
                isLoading = true
                scope.launch {
                    val endpoint = when {
                        isAdmin -> "$BASE_URL/api/admin/login"
                        isLogin -> "$BASE_URL/api/auth/login"
                        else   -> "$BASE_URL/api/auth/signup"
                    }
                    val json = JSONObject().apply {
                        if (isAdmin) put("username", email) else put("email", email)
                        put("password", password)
                        if (!isLogin && !isAdmin) put("name", name)
                    }
                    val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val request = Request.Builder().url(endpoint).post(body).build()

                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            runOnUiThread { isLoading = false; errorMessage = "Network error" }
                        }
                        override fun onResponse(call: Call, response: Response) {
                            val respBody = response.body?.string() ?: "{}"
                            runOnUiThread {
                                isLoading = false
                                if (response.isSuccessful) {
                                    val obj = JSONObject(respBody)
                                    if (isAdmin) {
                                        val token = obj.getString("token")
                                        onAdminLoginSuccess(token)
                                    } else {
                                        val token = obj.getString("token")
                                        val userObj = obj.getJSONObject("user")
                                        val user = UserInfo(userObj.getString("id"), userObj.getString("name"), userObj.getString("email"))
                                        onUserLoginSuccess(token, user)
                                    }
                                } else {
                                    errorMessage = JSONObject(respBody).optString("error", "Authentication failed")
                                }
                            }
                        }
                    })
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F172A))))) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Card(modifier = Modifier.size(80.dp).shadow(12.dp, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f))) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, Modifier.size(40.dp), tint = Color.White) }
                }
                Spacer(Modifier.height(16.dp))
                Text("Smart Locker", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("User", color = if (!isAdmin) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.5f))
                    Switch(
                        checked = isAdmin,
                        onCheckedChange = { newVal -> isAdmin = newVal; errorMessage = null; email = ""; password = ""; name = "" },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF60A5FA))
                    )
                    Text("Admin", color = if (isAdmin) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.5f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isAdmin) "Admin Login" else if (isLogin) "Welcome back!" else "Create an account",
                    fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(32.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f)), elevation = CardDefaults.cardElevation(8.dp)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        AnimatedVisibility(visible = !isLogin && !isAdmin, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Column {
                                OutlinedTextField(value = name, onValueChange = { name = it; errorMessage = null }, label = { Text("Full Name") }, leadingIcon = { Icon(Icons.Default.Person, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = outlinedTextFieldColors(), singleLine = true)
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; errorMessage = null },
                            label = { Text(if (isAdmin) "Username" else "Email") },
                            leadingIcon = { Icon(if (isAdmin) Icons.Default.Person else Icons.Default.Email, null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = outlinedTextFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = if (isAdmin) KeyboardType.Text else KeyboardType.Email),
                            singleLine = true
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; errorMessage = null },
                            label = { Text("Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) } },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = outlinedTextFieldColors(),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true
                        )
                        if (errorMessage != null) { Spacer(Modifier.height(8.dp)); Text(errorMessage!!, color = Color(0xFFEF4444), fontSize = 14.sp) }
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = performAuth,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                            enabled = !isLoading
                        ) {
                            if (isLoading) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                            else {
                                Text(
                                    when {
                                        isAdmin -> "Admin Sign In"
                                        isLogin -> "Sign In"
                                        else -> "Create Account"
                                    },
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (!isAdmin) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isLogin) "Don't have an account?" else "Already have an account?", color = Color.White.copy(alpha = 0.7f))
                        TextButton(onClick = { isLogin = !isLogin; errorMessage = null; email = ""; password = ""; name = "" }) {
                            Text(if (isLogin) "Sign Up" else "Sign In", color = Color(0xFF60A5FA), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White.copy(alpha = 0.8f),
        focusedLabelColor = Color(0xFF60A5FA), unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
        cursorColor = Color(0xFF60A5FA), focusedBorderColor = Color(0xFF60A5FA), unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
        focusedLeadingIconColor = Color(0xFF60A5FA), unfocusedLeadingIconColor = Color.White.copy(alpha = 0.6f),
        focusedTrailingIconColor = Color(0xFF60A5FA), unfocusedTrailingIconColor = Color.White.copy(alpha = 0.6f)
    )

    // -------------------- Location Selection --------------------
    @Composable
    fun LocationSelectionScreen(onLocationSelected: (LocationInfo) -> Unit) {
        var locations by remember { mutableStateOf<List<LocationInfo>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            val request = Request.Builder().url("$BASE_URL/locations").build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { runOnUiThread { isLoading = false } }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    if (body != null && body.isNotEmpty()) {
                        val arr = JSONArray(body)
                        val list = mutableListOf<LocationInfo>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            list.add(LocationInfo(obj.getString("id"), obj.getString("name")))
                        }
                        runOnUiThread { locations = list; isLoading = false }
                    } else { runOnUiThread { isLoading = false } }
                }
            })
        }
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(24.dp), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator(color = Color.White)
            else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Select Location", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(32.dp))
                    locations.forEach { loc ->
                        Button(
                            onClick = { onLocationSelected(loc) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                        ) { Text(loc.name) }
                    }
                }
            }
        }
    }

    // -------------------- Admin Screen --------------------
    @Composable
    fun AdminScreen(onLogout: () -> Unit) {
        var locations by remember { mutableStateOf<List<LocationInfo>>(emptyList()) }
        var selectedLocationId by remember { mutableStateOf("") }
        var lockerMap by remember { mutableStateOf<Map<String, LockerState>>(emptyMap()) }
        var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

        LaunchedEffect(Unit) { while (true) { currentTime = System.currentTimeMillis(); delay(1000) } }
        LaunchedEffect(Unit) {
            val request = Request.Builder().url("$BASE_URL/locations").build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    if (body != null && body.isNotEmpty()) {
                        val arr = JSONArray(body)
                        val list = mutableListOf<LocationInfo>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            list.add(LocationInfo(obj.getString("id"), obj.getString("name")))
                        }
                        runOnUiThread { locations = list; if (list.isNotEmpty()) selectedLocationId = list[0].id }
                    }
                }
            })
        }
        LaunchedEffect(selectedLocationId) {
            if (selectedLocationId.isNotEmpty()) {
                fetchLockers(selectedLocationId) { lockerMap = it }
                while (true) { fetchLockers(selectedLocationId) { lockerMap = it }; delay(2000) }
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ADMIN PANEL", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))) { Text("Logout") }
            }
            Spacer(Modifier.height(12.dp))
            if (locations.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(locations.find { it.id == selectedLocationId }?.name ?: "Select Location", color = Color.White)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        locations.forEach { loc ->
                            DropdownMenuItem(text = { Text(loc.name) }, onClick = { selectedLocationId = loc.id; expanded = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (lockerMap.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else {
                Column {
                    lockerMap.forEach { (id, state) ->
                        AdminLockerCard(
                            lockerId = id,
                            state = state,
                            currentTime = currentTime,
                            locationId = selectedLocationId,
                            onForceUnlock = { adminForceUnlock(selectedLocationId, id) },
                            onForceReset = { adminForceReset(selectedLocationId, id) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun AdminLockerCard(lockerId: String, state: LockerState?, currentTime: Long, locationId: String, onForceUnlock: () -> Unit, onForceReset: () -> Unit) {
        val status = state?.status ?: "AVAILABLE"
        val color = when (status) {
            "AVAILABLE" -> Color(0xFF4CAF50); "RESERVED" -> Color(0xFF9E9E9E); "WAITING_OTP" -> Color(0xFF2196F3)
            "OCCUPIED" -> Color(0xFFFF9800); "PENALTY" -> Color(0xFFF44336); else -> Color.Gray
        }
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lockerId, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(status, color = color, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                if (status == "OCCUPIED" && state?.sessionStart != null) {
                    val elapsed = ((currentTime - state.sessionStart) / 1000).toInt()
                    Text(String.format("%02d:%02d", elapsed / 60, elapsed % 60), color = Color.White)
                    Spacer(Modifier.width(12.dp))
                }
                Row {
                    Button(onClick = onForceUnlock, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)), modifier = Modifier.height(36.dp)) { Text("Unlock", fontSize = 12.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onForceReset, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), modifier = Modifier.height(36.dp)) { Text("Reset", fontSize = 12.sp) }
                }
            }
        }
    }

    private fun adminForceUnlock(locationId: String, lockerId: String) {
        val json = JSONObject().put("locationId", locationId).put("lockerId", lockerId)
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url("$BASE_URL/admin/force-unlock")
            .addHeader("Authorization", "Bearer $adminToken")
            .post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Unlock command sent", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun adminForceReset(locationId: String, lockerId: String) {
        val json = JSONObject().put("locationId", locationId).put("lockerId", lockerId)
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url("$BASE_URL/admin/force-reset")
            .addHeader("Authorization", "Bearer $adminToken")
            .post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Locker reset", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    // -------------------- Main Locker Screen (user) --------------------
    @Composable
    fun MainAppScreen(location: LocationInfo, onLogout: () -> Unit) {
        var lockerMap by remember { mutableStateOf<Map<String, LockerState>>(emptyMap()) }
        var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

        LaunchedEffect(Unit) { while (true) { currentTime = System.currentTimeMillis(); delay(1000) } }
        LaunchedEffect(location.id) {
            fetchLockers(location.id) { lockerMap = it }
            while (true) { fetchLockers(location.id) { lockerMap = it }; delay(2000) }
        }

        var showOtpDialog by remember { mutableStateOf(false) }
        var otpLockerId by remember { mutableStateOf("") }
        var otpValue by remember { mutableStateOf("") }
        _otpDialogTrigger.value?.let { trigger ->
            LaunchedEffect(trigger) { otpLockerId = trigger.first; otpValue = trigger.second; showOtpDialog = true; _otpDialogTrigger.value = null }
        }

        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("SMART LOCKER", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White); Text(location.name, fontSize = 14.sp, color = Color.Gray) }
                Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))) { Text("Logout") }
            }
            if (currentUser != null) Text("Welcome, ${currentUser!!.name}", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            if (lockerMap.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else {
                Row { LockerCard("L1", lockerMap["L1"], currentTime, location.id); LockerCard("L2", lockerMap["L2"], currentTime, location.id) }
                Row { LockerCard("L3", lockerMap["L3"], currentTime, location.id); LockerCard("L4", lockerMap["L4"], currentTime, location.id) }
            }
        }

        if (showOtpDialog) {
            AlertDialog(onDismissRequest = { showOtpDialog = false }, title = { Text("Enter OTP for $otpLockerId") }, text = {
                Column { Text("Please enter the 4-digit OTP"); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = otpValue, onValueChange = { if (it.length <= 4) otpValue = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
            }, confirmButton = {
                Button(onClick = { if (otpValue.length == 4) { verifyOtpFromApp(location.id, otpLockerId, otpValue); showOtpDialog = false } else Toast.makeText(this@MainActivity, "OTP must be 4 digits", Toast.LENGTH_SHORT).show() }) { Text("Verify") }
            }, dismissButton = { Button(onClick = { showOtpDialog = false }) { Text("Cancel") } })
        }
    }

    @Composable
    fun LockerCard(lockerId: String, state: LockerState?, currentTime: Long, locationId: String) {
        val context = LocalContext.current
        var showRateMenu by remember { mutableStateOf(false) }
        var showPickupDialog by remember { mutableStateOf(false) }
        var showPenaltyDialog by remember { mutableStateOf(false) }
        val status = state?.status ?: "AVAILABLE"
        val color = when (status) {
            "AVAILABLE" -> Color(0xFF4CAF50); "RESERVED" -> Color(0xFF9E9E9E); "WAITING_OTP" -> Color(0xFF2196F3)
            "OCCUPIED" -> Color(0xFFFF9800); "PENALTY" -> Color(0xFFF44336); else -> Color.Gray
        }
        Box(modifier = Modifier.padding(12.dp).size(140.dp).shadow(8.dp, RoundedCornerShape(16.dp)).background(color).clickable {
            when (status) {
                "AVAILABLE" -> { currentLocker = lockerId; currentLocationId = locationId; pendingPenaltyPayment = false; showRateMenu = true }
                "OCCUPIED" -> showPickupDialog = true
                "PENALTY" -> showPenaltyDialog = true
                else -> Toast.makeText(context, "Locker is $status", Toast.LENGTH_SHORT).show()
            }
        }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(lockerId, color = Color.White, fontWeight = FontWeight.Bold)
                Text(status.replace("_", " "), color = Color.White, fontSize = 12.sp)
                if (status == "OCCUPIED" && state?.sessionStart != null) {
                    val elapsed = ((currentTime - state.sessionStart) / 1000).toInt()
                    Text(String.format(Locale.getDefault(), "%02d:%02d", elapsed / 60, elapsed % 60), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (showRateMenu) {
            AlertDialog(onDismissRequest = { showRateMenu = false }, title = { Text("Select Duration for $lockerId") }, text = {
                Column { rateOptions.forEach { option -> Row(modifier = Modifier.fillMaxWidth().clickable { showRateMenu = false; reserveLocker(locationId, lockerId, option.durationMinutes, option.price * 100) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(option.label); Text("₹${option.price}") } } }
            }, confirmButton = {}, dismissButton = { Button(onClick = { showRateMenu = false }) { Text("Cancel") } })
        }
        if (showPickupDialog) {
            AlertDialog(onDismissRequest = { showPickupDialog = false }, title = { Text("Locker $lockerId") }, text = { Text("Choose pickup type") }, confirmButton = {
                Button(onClick = { showPickupDialog = false; requestPickup(locationId, lockerId, "partial") }) { Text("Partial") }
            }, dismissButton = { Button(onClick = { showPickupDialog = false; requestPickup(locationId, lockerId, "full") }) { Text("Full") } })
        }
        if (showPenaltyDialog) {
            AlertDialog(onDismissRequest = { showPenaltyDialog = false }, title = { Text("Penalty Period") }, text = { Text("Time exceeded. Pay ₹200 penalty.") }, confirmButton = {
                Button(onClick = { showPenaltyDialog = false; currentLocker = lockerId; currentLocationId = locationId; pendingPenaltyPayment = true; startPenaltyPayment(locationId, lockerId) }) { Text("Pay Penalty") }
            }, dismissButton = { Button(onClick = { showPenaltyDialog = false }) { Text("Cancel") } })
        }
    }

    // -------------------- API Helpers (user) --------------------
    private fun authenticatedRequest() = Request.Builder().addHeader("Authorization", "Bearer $authToken")

    private fun fetchLockers(locationId: String, callback: (Map<String, LockerState>) -> Unit) {
        val request = Request.Builder().url("$BASE_URL/lockers/$locationId").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (body != null && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val map = mutableMapOf<String, LockerState>()
                    json.keys().forEach { key ->
                        val obj = json.getJSONObject(key)
                        map[key] = LockerState(obj.getString("status"), if (obj.has("sessionStart") && !obj.isNull("sessionStart")) obj.optLong("sessionStart") else null)
                    }
                    runOnUiThread { callback(map) }
                }
            }
        })
    }

    private fun reserveLocker(locationId: String, lockerId: String, durationMinutes: Int, amount: Int) {
        val json = JSONObject().put("locationId", locationId).put("lockerId", lockerId).put("duration", durationMinutes).put("amount", amount)
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = authenticatedRequest().url("$BASE_URL/reserve").post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread { if (response.isSuccessful) startPayment(locationId, lockerId, false, amount) else Toast.makeText(this@MainActivity, "Not available", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun startPayment(locationId: String, lockerId: String, isPenalty: Boolean, amount: Int = 0) {
        val endpoint = if (isPenalty) "/pay-penalty" else "/create-order"
        val json = JSONObject().put("locationId", locationId).put("lockerId", lockerId)
        if (!isPenalty && amount > 0) json.put("amount", amount)
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = authenticatedRequest().url("$BASE_URL$endpoint").post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val orderBody = response.body?.string()
                if (orderBody != null) {
                    runOnUiThread { openRazorpay(JSONObject(orderBody)) }
                }
            }
        })
    }

    private fun startPenaltyPayment(locationId: String, lockerId: String) = startPayment(locationId, lockerId, true)

    private fun openRazorpay(order: JSONObject) {
        val checkout = Checkout()
        checkout.setKeyID("rzp_test_SXMpfOl9UATsO6")
        checkout.open(this, JSONObject().apply {
            put("name", "Smart Locker")
            put("description", if (pendingPenaltyPayment) "Penalty Payment (₹200)" else "Locker Booking")
            put("currency", "INR"); put("amount", order.getInt("amount")); put("order_id", order.getString("id"))
        })
    }

    override fun onPaymentSuccess(paymentId: String?) {
        val endpoint = if (pendingPenaltyPayment) "/penalty-success" else "/payment-success"
        val json = JSONObject().put("locationId", currentLocationId).put("lockerId", currentLocker)
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = authenticatedRequest().url("$BASE_URL$endpoint").post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val otp = JSONObject(response.body?.string() ?: "{}").optString("otp", "")
                runOnUiThread { _otpDialogTrigger.value = Pair(currentLocker, otp) }
                pendingPenaltyPayment = false
            }
        })
    }

    override fun onPaymentError(code: Int, response: String?) {
        Toast.makeText(this, "Payment Failed", Toast.LENGTH_SHORT).show()
        pendingPenaltyPayment = false
    }

    private fun requestPickup(locationId: String, lockerId: String, type: String) {
        val json = JSONObject().put("locationId", locationId).put("lockerId", lockerId).put("type", type)
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = authenticatedRequest().url("$BASE_URL/pickup").post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val otp = JSONObject(response.body?.string() ?: "{}").optString("otp", "")
                runOnUiThread { _otpDialogTrigger.value = Pair(lockerId, otp) }
            }
        })
    }

    private fun verifyOtpFromApp(locationId: String, lockerId: String, otp: String) {
        val json = JSONObject().put("locationId", locationId).put("lockerId", lockerId).put("otp", otp)
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = authenticatedRequest().url("$BASE_URL/verify-otp-app").post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { Toast.makeText(this@MainActivity, "Network error", Toast.LENGTH_SHORT).show() } }
            override fun onResponse(call: Call, response: Response) {
                val success = JSONObject(response.body?.string() ?: "{}").optBoolean("success")
                runOnUiThread { if (success) Toast.makeText(this@MainActivity, "Locker unlocked!", Toast.LENGTH_SHORT).show() else Toast.makeText(this@MainActivity, "Invalid OTP", Toast.LENGTH_SHORT).show() }
            }
        })
    }
}