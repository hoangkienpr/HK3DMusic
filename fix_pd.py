p = 'app/src/main/kotlin/com/hk/app/Components.kt'
src = open(p, encoding='utf-8').read()
start = src.index('fun PlaylistsDialog(')
# find the matching end: the function ends at the last '}' before EOF? Find from start to end of file (it's the last function)
# PlaylistsDialog is the last declaration, so end = len(src)
new_func = r'''
@Composable
fun PlaylistsDialog(
    playlists: List<Playlist>,
    songToAdd: Song? = null,
    currentSong: Song? = null,
    youtubePlaying: Boolean = false,
    accent: Color,
    onCreate: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: (Long) -> Unit,
    onAddCurrent: (Long) -> Unit = {},
    onAddYouTube: (Long) -> Unit = {},
    onRemoveSong: (Long, Long) -> Unit = { _, _ -> },
    onRemoveYouTube: (Long, String) -> Unit = { _, _ -> },
    onPlay: (Playlist) -> Unit,
    onPlaySong: (Song) -> Unit = {},
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Playlist?>(null) }
    val sel = selected?.let { s -> playlists.firstOrNull { it.id == s.id } } ?: selected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (sel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = null }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lai", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Text(sel.name, color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(if (songToAdd != null) "Them bai vao playlist" else "Playlist cua ban", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (sel != null) {
                    val pl = sel
                    Text("${pl.songIds.size} bai local - ${pl.youtubeIds.size} YouTube", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    if (pl.songIds.isEmpty() && pl.youtubeIds.isEmpty()) {
                        Text("Playlist trong. Dung nut +Bai / +YT o danh sach de them.", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(pl.songIds.size) { i ->
                                val sid = pl.songIds[i]
                                Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Bai local #$sid", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { onRemoveSong(pl.id, sid) }) { Text("Xoa", color = Color(0xFFFF6B6B), fontSize = 11.sp) }
                                    }
                                }
                            }
                            items(pl.youtubeIds.size) { i ->
                                val yid = pl.youtubeIds[i]
                                Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("YT: $yid", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        TextButton(onClick = { onRemoveYouTube(pl.id, yid) }) { Text("Xoa", color = Color(0xFFFF6B6B), fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onPlay(pl) },
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Phat tat ca", color = Color.Black, fontWeight = FontWeight.Bold) }
                } else {
                    if (songToAdd == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                placeholder = { Text("Ten playlist moi", color = Color.White.copy(alpha = 0.4f)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = accent,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                            Button(
                                onClick = { if (newName.isNotBlank()) { onCreate(newName); newName = "" } },
                                colors = ButtonDefaults.buttonColors(containerColor = accent),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("Tao", color = Color.Black, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (playlists.isEmpty()) {
                        Text("Chua co playlist nao.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(playlists.size) { i ->
                                val p = playlists[i]
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.White.copy(alpha = 0.06f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                    modifier = Modifier.fillMaxWidth().clickable { selected = p }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(p.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text("${p.songIds.size} bai local - ${p.youtubeIds.size} YouTube", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
                                        }
                                        if (songToAdd == null) {
                                            if (currentSong != null) {
                                                TextButton(onClick = { onAddCurrent(p.id) }) { Text("+Bai", color = accent, fontSize = 11.sp) }
                                            }
                                            if (youtubePlaying) {
                                                TextButton(onClick = { onAddYouTube(p.id) }) { Text("+YT", color = accent, fontSize = 11.sp) }
                                            }
                                            TextButton(onClick = { onDelete(p.id) }) { Text("Xoa", color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                                        } else {
                                            TextButton(onClick = { onAdd(p.id) }) { Text("Them", color = accent, fontSize = 12.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Dong", color = Color.White.copy(alpha = 0.7f)) } },
        containerColor = Color(0xFF101422)
    )
}
'''
src = src[:start] + new_func
open(p, 'w', encoding='utf-8').write(src)
print('rewrote PlaylistsDialog')
