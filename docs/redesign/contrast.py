"""WCAG 2.1 contrast for the Listening Room palette. Run: python3 contrast.py"""
def lum(h):
    h = h.lstrip('#'); r, g, b = [int(h[i:i+2], 16) / 255 for i in (0, 2, 4)]
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)

def cr(a, b):
    la, lb = sorted([lum(a), lum(b)], reverse=True); return (la + 0.05) / (lb + 0.05)

LIGHT = dict(ground='#F7F4EF', surface='#FFFDFA', raised='#EEE8DF', ink='#26201A', muted='#665A4E', faint='#8A7E71',
             line='#E3DBCF', amber='#F2A43A', onAmber='#2A1803', amberText='#8C4F00', amberSoft='#FCE6C4',
             onAmberSoft='#4A2C00', moss='#3B6B35', slate='#335C91', wine='#A1283E', record='#211B16',
             onRecord='#F7F0E6', onRecordMuted='#C1B3A3', recordLive='#FFB54D')
DARK = dict(ground='#15120F', surface='#1E1A16', raised='#29241F', ink='#F5EEE5', muted='#BCAF9F', faint='#877B6E',
            line='#38312A', amber='#FFB54D', onAmber='#2A1700', amberText='#FFC470', amberSoft='#4A3010',
            onAmberSoft='#FFE2B8', moss='#93CC88', slate='#9DBDF0', wine='#FF8FA0', record='#0E0B09',
            onRecord='#F7F0E6', onRecordMuted='#C1B3A3', recordLive='#FFB54D')

if __name__ == '__main__':
    for name, p in (('LIGHT', LIGHT), ('DARK', DARK)):
        print('==', name)
        for bg in ('ground', 'surface', 'raised', 'amberSoft'):
            print(f'{bg:9}', ' '.join(f'{fg}={cr(p[fg], p[bg]):.2f}' for fg in ('ink', 'muted', 'faint', 'amberText', 'moss', 'slate', 'wine')))
        for fg, bg in (('onAmber', 'amber'), ('onAmberSoft', 'amberSoft'), ('onRecord', 'record'),
                       ('onRecordMuted', 'record'), ('recordLive', 'record'), ('amber', 'ground')):
            print(f'  {fg}/{bg} = {cr(p[fg], p[bg]):.2f}')
