def lum(h):
    h=h.lstrip('#'); r,g,b=[int(h[i:i+2],16)/255 for i in (0,2,4)]
    f=lambda c: c/12.92 if c<=0.03928 else ((c+0.055)/1.055)**2.4
    return 0.2126*f(r)+0.7152*f(g)+0.0722*f(b)
def cr(a,b):
    la,lb=sorted([lum(a),lum(b)],reverse=True); return (la+0.05)/(lb+0.05)
L=dict(ground='#FBF6EF',surface='#FFFCF8',raised='#F2E9DD',ink='#2B2118',muted='#6A5B4D',faint='#8C7D6E',line='#E4D8C8',
 ember='#A8440F',onEmber='#FFFFFF',emberSoft='#F9DEC9',onEmberSoft='#5C2205',
 moss='#3B6B35',wine='#A1283E',ochre='#8A5A00',record='#1E1915',onRecord='#F6EEE4',onRecordMuted='#BFB1A2',recordLive='#FF9A5C')
D=dict(ground='#16120E',surface='#1F1A15',raised='#2A231D',ink='#F4ECE2',muted='#BBAD9E',faint='#857868',line='#3A3129',
 ember='#FF9E66',onEmber='#3A1604',emberSoft='#4A2510',onEmberSoft='#FFDCC6',
 moss='#93CC88',wine='#FF8FA0',ochre='#F0C060',record='#0D0A08',onRecord='#F6EEE4',onRecordMuted='#BFB1A2',recordLive='#FF9A5C')
for name,P in (('LIGHT',L),('DARK',D)):
  print('==',name)
  for bg in ('ground','surface','raised'):
    for fg in ('ink','muted','faint','ember','moss','wine','ochre'):
      print(f'{fg:6} on {bg:7} {cr(P[fg],P[bg]):5.2f}',end=' | ')
    print()
  for fg,bg in (('onEmber','ember'),('onEmberSoft','emberSoft'),('onRecord','record'),('onRecordMuted','record'),('recordLive','record'),('line','ground'),('ember','ground')):
    print(f'{fg} on {bg}: {cr(P[fg],P[bg]):.2f}')
