from __future__ import annotations

from typing import Any, Optional


class ApiError(Exception):
    """Ошибка, которая возвращается клиенту в едином формате api-contract.md, 1.3."""

    def __init__(self, status: int, code: str, message: str, details: Optional[dict[str, Any]] = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details or {}

    def body(self) -> dict[str, Any]:
        return {"error": {"code": self.code, "message": self.message, "details": self.details}}


def invalid_parameter(name: str, value: Any, message: str) -> ApiError:
    return ApiError(400, "INVALID_PARAMETER", message, {"parameter": name, "value": value})


def not_found(entity: str, key: str) -> ApiError:
    return ApiError(404, "NOT_FOUND", f"{entity} {key} not found", {"id": key})
