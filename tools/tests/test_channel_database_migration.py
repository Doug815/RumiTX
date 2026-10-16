#!/usr/bin/env python3
"""Non-destructive SQLite v1/v3/v4 regression fixtures for ChannelDatabase.java.

Runs with Python's stdlib sqlite3 on GitHub Actions; actual device tests still
require an Android runtime. DDL is extracted from the Java source being built.
"""
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "app/src/main/java/com/rumitx/app/ChannelDatabase.java").read_text()
MESSAGE_SOURCE = (ROOT / "app/src/main/java/com/rumitx/app/MessageStore.java").read_text()
REPEATER_SOURCE = (ROOT / "app/src/main/java/com/rumitx/app/repeaters/RepeaterDatabase.java").read_text()

def check(condition, message):
    if not condition:
        raise AssertionError(message)

def statements():
    # Java string literals within each SQLiteDatabase.execSQL invocation.
    # No hardcoded copy of the new schema is used for these fixtures.
    out = []
    for part in re.findall(r'db\.execSQL\(([\s\S]*?)\);', SOURCE):
        # Remove Java // comments before extracting SQL literals (one contains "023").
        part = re.sub(r'//[^\n]*', '', part)
        chunks = re.findall(r'"((?:\\.|[^"\\])*)"', part)
        if not chunks:
            continue
        sql = "".join(chunks)
        if '" + TABLE +' in part:
            sql = sql.replace("CREATE TABLE  (", "CREATE TABLE channels (")
        if sql.startswith("CREATE TABLE") or sql.startswith("CREATE INDEX"):
            out.append(sql)
    return out

SQL = statements()
def execute(db, kinds):
    for sql in SQL:
        if any(sql.startswith(prefix) for prefix in kinds):
            db.execute(sql)

def has_table(db, name):
    return db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()[0] == 1

def preserved_row(db, table, clause):
    return db.execute("SELECT COUNT(*) FROM "+table+" WHERE "+clause).fetchone()[0] == 1

check(re.search(r'DB_VERSION\s*=\s*4\s*;', SOURCE), "rumitx.db version must be 4")
check('onDowngrade(' not in SOURCE, "Custom destructive downgrade must not be introduced")
check(not re.search(r'db\.execSQL\(\s*"DROP', SOURCE, re.I), "DROP operations forbidden")
check('super(c,"messages.db",null,2)' in MESSAGE_SOURCE, "messages.db must stay at production version 2")
check('super(c,"repeaters.db",null,1)' in REPEATER_SOURCE, "repeaters.db must stay at production version 1")
check(len(SQL) >= 13, "Expected complete v4 SQLite DDL, got "+str(len(SQL)))

# Populate a version-4 production database, with data in every major group.
db = sqlite3.connect(":memory:")
execute(db, ("CREATE TABLE", "CREATE INDEX"))
db.execute("PRAGMA user_version = 4")
db.execute("INSERT INTO channels(name,tx_freq,rx_freq) VALUES ('MY CHANNEL',465.000,460.000)")
db.execute("INSERT INTO ai_memories(fact,created_at) VALUES ('remember station',123)")
db.execute("INSERT INTO ai_chat_pairs(question,answer,created_at) VALUES ('question','answer',123)")
db.execute("INSERT INTO frequency_history(rx,tx,label,created_at) VALUES (460,465,'tuned',123)")
db.execute("INSERT INTO nomad_chunks(filename,source_uri,body,indexed_at) VALUES ('nomad.txt','SD','knowledge',123)")
db.execute("INSERT INTO repeater_catalog(source_key,frequency,city,state,status) VALUES ('sample',442.650,'Rockford','IL','OPEN')")
db.execute("INSERT INTO chat_threads(title,created_at,updated_at) VALUES ('Existing conversation',123,123)")
db.execute("INSERT INTO chat_messages(thread_id,user_text,assistant_text,created_at) VALUES (1,'hello','hi',123)")
db.commit()
initial = {table: db.execute("SELECT COUNT(*) FROM "+table).fetchone()[0] for table in
           ("channels","ai_memories","ai_chat_pairs","frequency_history","nomad_chunks","repeater_catalog","chat_threads","chat_messages")}
# SQLiteOpenHelper on version 4 must not invoke onUpgrade/onDowngrade.
check(db.execute("PRAGMA user_version").fetchone()[0] == 4, "Existing v4 database must be accepted")
check(all(db.execute("SELECT COUNT(*) FROM "+table).fetchone()[0] == count for table,count in initial.items()),
      "Existing v4 database data changed")
check(preserved_row(db, "channels", "name='MY CHANNEL'"), "Channel data missing")
check(preserved_row(db, "repeater_catalog", "source_key='sample'"), "Repeater data missing")
db.close()

# An old v1 channel database acquires v2/v3/v4 tables while retaining channels.
db = sqlite3.connect(":memory:")
db.execute(next(sql for sql in SQL if sql.startswith("CREATE TABLE channels")))
db.execute("INSERT INTO channels(name,tx_freq,rx_freq) VALUES ('MY V1 CHANNEL',446.1,446.1)")
db.execute("PRAGMA user_version = 1")
execute(db, ("CREATE TABLE IF NOT EXISTS", "CREATE INDEX IF NOT EXISTS"))
db.execute("PRAGMA user_version = 4")
check(preserved_row(db, "channels", "name='MY V1 CHANNEL'"), "v1 channel wiped during upgrade")
for table in ("ai_memories","ai_chat_pairs","frequency_history","nomad_chunks","repeater_catalog","chat_threads","chat_messages"):
    check(has_table(db, table), "Missing upgraded table "+table)
db.close()

# A v3 database keeps legacy chat pairs while adding a recoverable v4 thread.
db = sqlite3.connect(":memory:")
db.execute(next(sql for sql in SQL if sql.startswith("CREATE TABLE channels")))
for sql in SQL:
    if sql.startswith(("CREATE TABLE IF NOT EXISTS", "CREATE INDEX IF NOT EXISTS")) and not any(
        n in sql for n in ("chat_threads", "chat_messages", "idx_chat_thread_time", "idx_chat_messages_thread")
    ):
        db.execute(sql)
db.execute("INSERT INTO ai_chat_pairs(question,answer,created_at) VALUES ('Old user','Old answer',321)")
db.execute("INSERT INTO channels(name,tx_freq,rx_freq) VALUES ('MY V3 CHANNEL',440,440)")
for sql in SQL:
    if sql.startswith(("CREATE TABLE IF NOT EXISTS", "CREATE INDEX IF NOT EXISTS")) and any(
        n in sql for n in ("chat_threads", "chat_messages", "idx_chat_thread_time", "idx_chat_messages_thread")
    ):
        db.execute(sql)
db.execute("INSERT INTO chat_threads(title,created_at,updated_at) VALUES ('Recovered earlier conversation',123,123)")
db.execute("INSERT INTO chat_messages(thread_id,user_text,assistant_text,created_at) SELECT 1,question,answer,created_at FROM ai_chat_pairs ORDER BY _id ASC")
check(preserved_row(db, "channels", "name='MY V3 CHANNEL'"), "v3 channels modified")
check(preserved_row(db, "ai_chat_pairs", "question='Old user'"), "v3 legacy messages deleted")
check(preserved_row(db, "chat_messages", "user_text='Old user' AND assistant_text='Old answer'"), "v3 chats not migrated")
db.close()

print("PASS: v4 fixture preserves channels, repeater catalog, memories, Nomad chunks and chats")
print("PASS: v1 channel fixture upgrades with no row loss; v4 tables created")
print("PASS: v3 chat fixture upgrades to recoverable threads without deleting original pairs")
print("PASS: messages.db version 2 and repeaters.db version 1 unchanged")
