p = 'app/src/main/kotlin/com/hk/app/WebRemoteServer.kt'
s = open(p, encoding='utf-8').read()
old = """function renderList(d){
  var box=document.getElementById('list');
  if(!d.playlist) return;
  var key=d.playlist.map(function(s){return s.pos+':'+s.index+':'+s.title;}).join('|');
  if(key===lastKey){
    var items=box.children;
    for(var i=0;i<items.length;i++) items[i].className='item'+(i===d.index?' active':'');
    return;
  }
  var html='';
  for(var i=0;i<d.playlist.length;i++){
    var s=d.playlist[i];
    var pos=(s.pos===undefined?i:s.pos);
    html+='<div class="item'+(pos===d.index?' active':'')+'">'
      +'<label class="pick"><input type="checkbox" value="'+s.index+'"'+(selected.has(s.index)?' checked':'')+' onclick="toggleSel(this)"></label>'
      +'<span class="nm" onclick="playPos('+pos+')">'+(i+1)+'. '+escapeHtml(s.title)+'</span>'
      +'<span>'+fmt(s.duration)+'</span>'
      +'<button class="castBtn" title="PhÃ¡t lÃªn TV" onclick="castPlay('+s.index+')">ðŸ“º</button>'
      +'<button class="del" title="Bá» khá»i danh sÃ¡ch phÃ¡t" onclick="removeAt('+pos+')">âœ•</button>'
      +'</div>';
  }
  box.innerHTML=html;
  lastKey=key;
}"""
new = """function renderList(d){
  var box=document.getElementById('list');
  if(!d.playlist) return;
  var q=((document.getElementById('plSearch')||{}).value||'').toLowerCase();
  var key=d.playlist.map(function(s){return s.pos+':'+s.index+':'+s.title;}).join('|')+'|q='+q;
  if(key===lastKey){
    var items=box.children;
    for(var i=0;i<items.length;i++) items[i].className='item'+(i===d.index?' active':'');
    return;
  }
  var html='';
  for(var i=0;i<d.playlist.length;i++){
    var s=d.playlist[i];
    if(q && (s.title||'').toLowerCase().indexOf(q)<0 && (s.artist||'').toLowerCase().indexOf(q)<0) continue;
    var pos=(s.pos===undefined?i:s.pos);
    html+='<div class="item'+(pos===d.index?' active':'')+'">'
      +'<label class="pick"><input type="checkbox" value="'+s.index+'"'+(selected.has(s.index)?' checked':'')+' onclick="toggleSel(this)"></label>'
      +'<span class="nm" onclick="playPos('+pos+')">'+(i+1)+'. '+escapeHtml(s.title)+'</span>'
      +'<span>'+fmt(s.duration)+'</span>'
      +'<button class="castBtn" title="PhÃ¡t lÃªn TV" onclick="castPlay('+s.index+')">ðŸ“º</button>'
      +'<button class="del" title="Bá» khá»i danh sÃ¡ch phÃ¡t" onclick="removeAt('+pos+')">âœ•</button>'
      +'</div>';
  }
  if(!html) html='<div class="item"><span class="nm">KhÃ´ng tÃ¬m tháº¥y bÃ i phÃ¹ há»£p</span></div>';
  box.innerHTML=html;
  lastKey=key;
}"""
assert old in s, 'old not found'
s = s.replace(old, new)
open(p, 'w', encoding='utf-8').write(s)
print('done')
