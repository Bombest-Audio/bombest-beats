package com.bombest.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class DashboardStats(
    val total_plays: Int,
    val unique_tracks: Int,
    val users: List<UserStats>? = null
)

data class UserStats(
    val user_id: Int,
    val username: String,
    val play_count: Int
)

data class TrackStats(
    val track_id: Int,
    val title: String,
    val play_count: Int
)

data class DashboardResponse(
    val total_plays: Int,
    val unique_tracks: Int,
    val top_tracks: List<TrackStats>,
    val users: List<UserStats>?
)

interface DashboardApi {
    @GET("dashboard")
    suspend fun getDashboard(@Query("user_id") userId: Int? = null): DashboardResponse
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onBack: () -> Unit) {
    var stats by remember { mutableStateOf<DashboardResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedUserId by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    
    val dashboardApi = remember {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        
        Retrofit.Builder()
            .baseUrl("https://bom.best/beats/api/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(DashboardApi::class.java)
    }
    
    fun loadDashboard(userId: Int? = null) {
        scope.launch {
            isLoading = true
            try {
                stats = withContext(Dispatchers.IO) {
                    dashboardApi.getDashboard(userId)
                }
            } catch (e: Exception) {
                error = e.message
            }
            isLoading = false
        }
    }
    
    LaunchedEffect(Unit) {
        loadDashboard()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF15192A),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0A0D14)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFFE90060)
                )
            } else if (error != null) {
                Text(
                    text = "Error: $error",
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (stats != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // User filter
                    stats?.users?.let { users ->
                        if (users.isNotEmpty()) {
                            item {
                                UserFilter(
                                    users = users,
                                    selectedUserId = selectedUserId,
                                    onUserSelected = { userId ->
                                        selectedUserId = userId
                                        loadDashboard(userId)
                                    }
                                )
                            }
                        }
                    }
                    
                    // Stats cards
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatCard(
                                title = "Total Plays",
                                value = stats?.total_plays?.toString() ?: "0",
                                modifier = Modifier.weight(1f)
                            )
                            StatCard(
                                title = "Unique Tracks",
                                value = stats?.unique_tracks?.toString() ?: "0",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    // Top tracks
                    item {
                        Text(
                            text = "Top Tracks",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    
                    items(stats?.top_tracks ?: emptyList()) { track ->
                        TrackStatItem(track)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserFilter(
    users: List<UserStats>,
    selectedUserId: Int?,
    onUserSelected: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedUser = users.find { it.user_id == selectedUserId }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Filter by User",
                fontSize = 14.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedUser?.username ?: "All Users",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE90060),
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("All Users") },
                        onClick = {
                            onUserSelected(null)
                            expanded = false
                        }
                    )
                    users.forEach { user ->
                        DropdownMenuItem(
                            text = { Text("${user.username} (${user.play_count} plays)") },
                            onClick = {
                                onUserSelected(user.user_id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE90060)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun TrackStatItem(track: TrackStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${track.play_count} plays",
                color = Color(0xFFE90060),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
