import sqlite3
import os
import sys

def add_user(username, password):
    db_path = os.path.abspath(r'app/src/main/assets/inventory.db')
    if not os.path.exists(db_path):
        print(f"Error: Database not found at {db_path}")
        return

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    cur.execute("""
    CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT UNIQUE,
        password TEXT
    );
    """)

    cur.execute("SELECT id, username FROM users WHERE username = ?", (username,))
    existing = cur.fetchone()
    if existing:
        cur.execute("UPDATE users SET password = ? WHERE username = ?", (password, username))
        print(f"Updated password for existing user: '{username}'")
    else:
        cur.execute("INSERT INTO users (username, password) VALUES (?, ?)", (username, password))
        print(f"Successfully added user: '{username}' with password: '{password}'")

    conn.commit()

    print("\n--- Current Users in Database ---")
    cur.execute("SELECT id, username, password FROM users;")
    for row in cur.fetchall():
        print(f"ID: {row[0]} | Username: {row[1]} | Password: {row[2]}")

    conn.close()

if __name__ == '__main__':
    if len(sys.argv) >= 3:
        u = sys.argv[1]
        p = sys.argv[2]
    else:
        print("Usage: python add_user.py <username> <password>")
        print("Example: python add_user.py manager pass123")
        sys.exit(1)

    add_user(u, p)
