"""Repository pattern: data-access objects that encapsulate all queries.

Services depend on repositories, never on raw queries, keeping persistence
concerns isolated and swappable/testable.
"""
from __future__ import annotations

import uuid
from typing import Generic, List, Optional, Sequence, Type, TypeVar

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import models

M = TypeVar("M")


class BaseRepository(Generic[M]):
    model: Type[M]

    def __init__(self, db: Session):
        self.db = db

    def get(self, id_: uuid.UUID) -> Optional[M]:
        return self.db.get(self.model, id_)

    def add(self, obj: M) -> M:
        self.db.add(obj)
        self.db.flush()
        return obj

    def delete(self, obj: M) -> None:
        self.db.delete(obj)


class UserRepository(BaseRepository[models.User]):
    model = models.User

    def get_by_email(self, email: str) -> Optional[models.User]:
        return self.db.execute(
            select(models.User).where(models.User.email == email.lower())
        ).scalar_one_or_none()


class CategoryRepository(BaseRepository[models.Category]):
    model = models.Category

    def list(self, user_id: uuid.UUID, include_archived: bool = False
             ) -> Sequence[models.Category]:
        q = select(models.Category).where(models.Category.user_id == user_id)
        if not include_archived:
            q = q.where(models.Category.is_archived.is_(False))
        return self.db.execute(q.order_by(models.Category.name)).scalars().all()

    def get_for_user(self, user_id: uuid.UUID, id_: uuid.UUID
                     ) -> Optional[models.Category]:
        return self.db.execute(
            select(models.Category).where(
                models.Category.id == id_, models.Category.user_id == user_id
            )
        ).scalar_one_or_none()


class TransactionRepository(BaseRepository[models.Transaction]):
    model = models.Transaction

    def get_for_user(self, user_id: uuid.UUID, id_: uuid.UUID
                     ) -> Optional[models.Transaction]:
        return self.db.execute(
            select(models.Transaction).where(
                models.Transaction.id == id_,
                models.Transaction.user_id == user_id,
                models.Transaction.is_deleted.is_(False),
            )
        ).scalar_one_or_none()

    def get_by_client_id(self, user_id: uuid.UUID, client_id: str
                         ) -> Optional[models.Transaction]:
        return self.db.execute(
            select(models.Transaction).where(
                models.Transaction.user_id == user_id,
                models.Transaction.client_id == client_id,
            )
        ).scalar_one_or_none()

    def search(self, user_id: uuid.UUID, *, q: Optional[str] = None,
               category_id: Optional[uuid.UUID] = None,
               type_: Optional[str] = None,
               min_amount: Optional[float] = None, max_amount: Optional[float] = None,
               date_from=None, date_to=None, status: Optional[str] = None,
               page: int = 1, page_size: int = 50):
        base = select(models.Transaction).where(
            models.Transaction.user_id == user_id,
            models.Transaction.is_deleted.is_(False),
        )
        if q:
            like = f"%{q.lower()}%"
            base = base.where(
                func.lower(func.coalesce(models.Transaction.merchant_name, "")).like(like)
                | func.lower(func.coalesce(models.Transaction.raw_merchant, "")).like(like)
                | func.lower(func.coalesce(models.Transaction.notes, "")).like(like)
                | func.lower(func.coalesce(models.Transaction.reference_number, "")).like(like)
            )
        if category_id is not None:
            base = base.where(models.Transaction.category_id == category_id)
        if type_ is not None:
            base = base.where(models.Transaction.type == type_)
        if min_amount is not None:
            base = base.where(models.Transaction.amount >= min_amount)
        if max_amount is not None:
            base = base.where(models.Transaction.amount <= max_amount)
        if date_from is not None:
            base = base.where(models.Transaction.occurred_at >= date_from)
        if date_to is not None:
            base = base.where(models.Transaction.occurred_at <= date_to)
        if status is not None:
            base = base.where(models.Transaction.status == status)

        total = self.db.execute(
            select(func.count()).select_from(base.subquery())
        ).scalar_one()
        page = max(1, page)
        page_size = min(max(1, page_size), 200)
        rows = self.db.execute(
            base.order_by(models.Transaction.occurred_at.desc(),
                          models.Transaction.created_at.desc())
            .limit(page_size).offset((page - 1) * page_size)
        ).scalars().all()
        return rows, total

    def changed_since(self, user_id: uuid.UUID, since) -> List[models.Transaction]:
        q = select(models.Transaction).where(models.Transaction.user_id == user_id)
        if since is not None:
            q = q.where(models.Transaction.updated_at > since)
        return list(self.db.execute(
            q.order_by(models.Transaction.updated_at.desc())
        ).scalars().all())


class FraudRepository(BaseRepository[models.FraudAlert]):
    model = models.FraudAlert

    def list(self, user_id: uuid.UUID, status: Optional[str] = None
             ) -> Sequence[models.FraudAlert]:
        q = select(models.FraudAlert).where(models.FraudAlert.user_id == user_id)
        if status:
            q = q.where(models.FraudAlert.status == status)
        return self.db.execute(
            q.order_by(models.FraudAlert.created_at.desc())
        ).scalars().all()

    def get_for_user(self, user_id: uuid.UUID, id_: uuid.UUID
                     ) -> Optional[models.FraudAlert]:
        return self.db.execute(
            select(models.FraudAlert).where(
                models.FraudAlert.id == id_, models.FraudAlert.user_id == user_id
            )
        ).scalar_one_or_none()


class ReceiptRepository(BaseRepository[models.Receipt]):
    model = models.Receipt

    def list(self, user_id: uuid.UUID, transaction_id: Optional[uuid.UUID] = None
             ) -> Sequence[models.Receipt]:
        q = select(models.Receipt).where(models.Receipt.user_id == user_id)
        if transaction_id is not None:
            q = q.where(models.Receipt.transaction_id == transaction_id)
        return self.db.execute(
            q.order_by(models.Receipt.created_at.desc())
        ).scalars().all()

    def get_for_user(self, user_id: uuid.UUID, id_: uuid.UUID
                     ) -> Optional[models.Receipt]:
        return self.db.execute(
            select(models.Receipt).where(
                models.Receipt.id == id_, models.Receipt.user_id == user_id
            )
        ).scalar_one_or_none()
