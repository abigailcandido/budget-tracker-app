"""make others buckets per tracker

Revision ID: 9d88eed160d5
Revises: c56cae20c8fa
Create Date: 2026-10-28 13:08:54.645011

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '9d88eed160d5'
down_revision: Union[str, Sequence[str], None] = 'c56cae20c8fa'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Give every tracker its own Others, in place of the shared ones.

    Until now each kind had one global Others, and so did each global category
    with sub-categories, shared by every tracker that used it. Each tracker
    using one gets a copy that belongs to it alone, switched on if the shared
    one was, and that tracker's rows move to the copy: every version and every
    reversal, so each entry's history stays together and every total is
    unchanged. A shared Others with nothing left pointing at it is removed.

    Data only, so Alembic could not generate it. There is no schema change.
    """
    bind = op.get_bind()

    shared = bind.execute(
        sa.text(
            """
            SELECT id, user_id, name, kind, parent_id
            FROM categories
            WHERE lower(trim(name)) = 'others'
              AND main_tracker_id IS NULL
              AND savings_tracker_id IS NULL
            ORDER BY id
            """
        )
    ).fetchall()

    for others in shared:
        linked = set(
            bind.execute(
                sa.text(
                    """
                    SELECT main_tracker_id FROM tracker_categories
                    WHERE category_id = :id AND main_tracker_id IS NOT NULL
                    """
                ),
                {"id": others.id},
            ).scalars()
        )
        used = set(
            bind.execute(
                sa.text(
                    """
                    SELECT DISTINCT w.main_tracker_id
                    FROM transactions t JOIN wallets w ON w.id = t.wallet_id
                    WHERE t.category_id = :id AND w.main_tracker_id IS NOT NULL
                    """
                ),
                {"id": others.id},
            ).scalars()
        )

        for tracker_id in sorted(linked | used):
            copy_id = bind.execute(
                sa.text(
                    """
                    INSERT INTO categories (user_id, name, kind, parent_id, main_tracker_id)
                    VALUES (:user_id, :name, :kind, :parent_id, :tracker_id)
                    RETURNING id
                    """
                ),
                {
                    "user_id": others.user_id,
                    "name": others.name,
                    "kind": others.kind,
                    "parent_id": others.parent_id,
                    "tracker_id": tracker_id,
                },
            ).scalar_one()

            if tracker_id in linked:
                bind.execute(
                    sa.text(
                        """
                        INSERT INTO tracker_categories (category_id, main_tracker_id)
                        VALUES (:copy_id, :tracker_id)
                        """
                    ),
                    {"copy_id": copy_id, "tracker_id": tracker_id},
                )

            for table in ("transactions", "recurring_rules"):
                bind.execute(
                    sa.text(
                        f"""
                        UPDATE {table} SET category_id = :copy_id
                        WHERE category_id = :id
                          AND wallet_id IN (
                              SELECT id FROM wallets WHERE main_tracker_id = :tracker_id
                          )
                        """
                    ),
                    {"copy_id": copy_id, "id": others.id, "tracker_id": tracker_id},
                )

        still_used = bind.execute(
            sa.text(
                """
                SELECT 1 FROM transactions WHERE category_id = :id
                UNION ALL SELECT 1 FROM recurring_rules WHERE category_id = :id
                UNION ALL SELECT 1 FROM budget_limits WHERE category_id = :id
                UNION ALL SELECT 1 FROM categories WHERE parent_id = :id
                LIMIT 1
                """
            ),
            {"id": others.id},
        ).first()
        if still_used is None:
            bind.execute(
                sa.text("DELETE FROM tracker_categories WHERE category_id = :id"),
                {"id": others.id},
            )
            bind.execute(sa.text("DELETE FROM categories WHERE id = :id"), {"id": others.id})


def downgrade() -> None:
    """Nothing to undo in the schema.

    The per-tracker copies are left as they are: merging them back into one
    shared Others would mix trackers' entries together again, and the older
    code works with them as they are.
    """
    pass