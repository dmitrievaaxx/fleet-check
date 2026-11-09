//go:build tools

// Библиотеки платформы, которые код шаблона пока не импортирует (testify — если удалить пример
// теста). Они есть на проверке вместе с chi и pgx: этот файл держит их в go.mod, чтобы
// `go mod tidy` их не удалил. В сервис он не собирается.
package tools

import (
	_ "github.com/stretchr/testify/assert"
	_ "github.com/stretchr/testify/require"
)
