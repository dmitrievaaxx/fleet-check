// Информационная система автотранспортного предприятия: chi и PostgreSQL.
//
// Платформа собирает сервис командой `go build ./cmd/server` и запускает бинарник на порту из
// PORT (8000), адрес базы — в DATABASE_URL. Схема создаётся при старте и переживает перезапуск.
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"fleet/internal/api"
	"fleet/internal/db"
)

// connect ждёт базу до 60 секунд: в docker compose сервис может стартовать раньше неё.
func connect(ctx context.Context) (*pgxpool.Pool, error) {
	deadline := time.Now().Add(60 * time.Second)
	for {
		pool, err := db.Connect(ctx)
		if err == nil {
			return pool, nil
		}
		if time.Now().After(deadline) {
			return nil, err
		}
		log.Printf("база пока недоступна: %v", err)
		time.Sleep(time.Second)
	}
}

func main() {
	ctx := context.Background()

	pool, err := connect(ctx)
	if err != nil {
		log.Fatalf("база недоступна: %v", err)
	}
	if err := db.Migrate(ctx, pool); err != nil {
		log.Fatalf("схема не создана: %v", err)
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8000"
	}
	server := &http.Server{
		Addr:              ":" + port,
		Handler:           api.NewRouter(db.NewStore(pool)),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("сервис слушает порт %s", port)
	log.Fatal(server.ListenAndServe())
}
