"""add category kind and opening balance transactions

Revision ID: fb23975e1bb6
Revises: a549d38cdb82
Create Date: 2026-10-24 20:48:37.836044

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'fb23975e1bb6'
down_revision: Union[str, Sequence[str], None] = 'a549d38cdb82'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Every category that already exists is an expense category, which is all
    # the app has offered so far.
    op.add_column('categories', sa.Column('kind', sa.String(length=10), server_default='expense', nullable=False))

    # Wallets created before opening balances became transactions keep their
    # starting amount only in the balance column. Recording it as a transaction
    # dated the day the wallet was made means every wallet's history now starts
    # from its opening balance, so reports can rebuild a running balance from
    # the transaction log alone.
    op.execute(
        """
        INSERT INTO transactions
            (user_id, wallet_id, amount, type, description, transaction_date, is_edited_instance)
        SELECT w.user_id, w.id, w.balance, 'opening', 'Opening balance', w.created_at::date, false
        FROM wallets w
        WHERE w.balance <> 0
          AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.wallet_id = w.id)
        """
    )


def downgrade() -> None:
    """Downgrade schema."""
    # The balance column already holds each wallet's total, so removing the
    # opening transactions loses nothing.
    op.execute("DELETE FROM transactions WHERE type = 'opening'")
    op.drop_column('categories', 'kind')