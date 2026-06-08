"""Category CRUD."""
from __future__ import annotations

import uuid
from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import models
from app.core.database import get_db
from app.core.deps import get_current_user
from app.repositories import CategoryRepository
from app.schemas import CategoryCreate, CategoryOut, CategoryUpdate, MessageOut

router = APIRouter(prefix="/categories", tags=["categories"])


@router.get("", response_model=List[CategoryOut])
def list_categories(include_archived: bool = Query(False),
                    user: models.User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    return [CategoryOut.model_validate(c)
            for c in CategoryRepository(db).list(user.id, include_archived)]


@router.post("", response_model=CategoryOut, status_code=201)
def create_category(body: CategoryCreate, user: models.User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    cat = models.Category(user_id=user.id, name=body.name.strip(), type=body.type,
                          icon=body.icon, color=body.color)
    db.add(cat)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "A category with this name already exists")
    db.refresh(cat)
    return CategoryOut.model_validate(cat)


@router.patch("/{category_id}", response_model=CategoryOut)
def update_category(category_id: uuid.UUID, body: CategoryUpdate,
                    user: models.User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    repo = CategoryRepository(db)
    cat = repo.get_for_user(user.id, category_id)
    if not cat:
        raise HTTPException(404, "Category not found")
    if body.name is not None:
        cat.name = body.name.strip()
    if body.icon is not None:
        cat.icon = body.icon
    if body.color is not None:
        cat.color = body.color
    if body.is_archived is not None:
        cat.is_archived = body.is_archived
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "A category with this name already exists")
    db.refresh(cat)
    return CategoryOut.model_validate(cat)


@router.delete("/{category_id}", response_model=MessageOut)
def delete_category(category_id: uuid.UUID, user: models.User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    repo = CategoryRepository(db)
    cat = repo.get_for_user(user.id, category_id)
    if not cat:
        raise HTTPException(404, "Category not found")
    repo.delete(cat)
    db.commit()
    return MessageOut(message="Category deleted")
