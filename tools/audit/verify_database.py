"""Execute the production DAO SQL against the checked-in SQLite schema. Python 3 only."""
from pathlib import Path
import json, re, sqlite3

root = Path(__file__).resolve().parents[2]
database = root / 'core/database'
schema = json.loads((database / 'schemas/com.harmony.core.database.HarmonyDatabase/2.json').read_text())['database']
db = sqlite3.connect(':memory:')
db.row_factory = sqlite3.Row
db.execute('PRAGMA foreign_keys=ON')
entities = {e['tableName']: e for e in schema['entities']}
for name, entity in entities.items():
    db.execute(entity['createSql'].replace('${TABLE_NAME}', name))
    for index in entity['indices']:
        db.execute(index['createSql'].replace('${TABLE_NAME}', name))
for view in schema['views']:
    db.execute(view['createSql'].replace('${VIEW_NAME}', view['viewName']))

def insert(table, **values):
    fields = entities[table]['fields']
    defaults = {'TEXT': '', 'INTEGER': 1, 'REAL': 0.5, 'BLOB': b'\0'}
    row = {f['columnName']: defaults[f['affinity']] if f['notNull'] else None for f in fields}
    row.update(values)
    db.execute(f'INSERT INTO {table} ({",".join(row)}) VALUES ({",".join("?" for _ in row)})', tuple(row.values()))

insert('songs', id=1, uri='content://song/1', title='Original', artist='Original artist', album='Original album')
insert('playlists', id=1, name='Test')
insert('playlist_songs', playlistId=1, songId=1, position=0)
insert('favorites', songId=1)
insert('play_history', songId=1, completed=1)
insert('analysis_results', songId=1)
insert('song_edits', songId=1, title='Edited title', artist='Edited artist', album='Edited album', artworkCleared=1)

dao = database / 'src/main/kotlin/com/harmony/core/database/dao'
def query(file, method):
    text = (dao / (file + '.kt')).read_text()
    matches = re.findall(r'@Query\(\s*("""[\s\S]*?"""|"[^"\n]*")\s*\)\s*(?:suspend\s+)?fun\s+(\w+)', text)
    return next(sql.strip('"').strip() for sql, name in matches if name == method)

for file, method in [('PlaylistDao', 'observeSongs'), ('HistoryDao', 'observeFavorites'),
                     ('HistoryDao', 'observeRecentlyPlayed'), ('HistoryDao', 'observeMostPlayed'),
                     ('AnalysisDao', 'observeHighestEnergy'), ('AnalysisDao', 'observeLowestEnergy')]:
    row = db.execute(query(file, method), {'playlistId': 1, 'limit': 24}).fetchone()
    assert row['title'] == 'Edited title' and row['artist'] == 'Edited artist' and row['album'] == 'Edited album', method
    assert row['artworkUri'] is None, method

db.execute(query('HistoryDao', 'addFavoriteIfPresent'), {'songId': 999, 'atMillis': 100})
assert db.execute('SELECT COUNT(*) FROM favorites').fetchone()[0] == 1
db.execute(query('HistoryDao', 'recordIfPresent'), {'songId': 999, 'atMillis': 100, 'completed': 1})
assert db.execute('SELECT COUNT(*) FROM play_history').fetchone()[0] == 1
db.execute('DELETE FROM songs WHERE id=1')
for table in ['favorites', 'playlist_songs', 'play_history', 'analysis_results']:
    assert db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0] == 0
print('PASS: 6 effective-metadata queries, 2 missing-song writes, deletion cascades in 4 tables')
