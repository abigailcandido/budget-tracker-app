"""link transfer fees to their transfers

Revision ID: db08adef8989
Revises: 17b759556f31
Create Date: 2026-10-30 13:00:05.257187

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'db08adef8989'
down_revision: Union[str, Sequence[str], None] = '17b759556f31'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Fees charged before this have no link, so they stay ordinary expenses
    # that are corrected and deleted on their own.
    op.add_column('transactions', sa.Column('fee_for_id', sa.Integer(), nullable=True))
    # Unique: a transfer has at most one fee.
    op.create_index(
        op.f('ix_transactions_fee_for_id'),
        'transactions',
        ['fee_for_id'],
        unique=True,
    )
    # Named the way PostgreSQL would name it, like the other links.
    op.create_foreign_key(
        'transactions_fee_for_id_fkey',
        'transactions',
        'transactions',
        ['fee_for_id'],
        ['id'],
        ondelete='SET NULL',
    )


def downgrade() -> None:
    """Downgrade schema."""
    # The fees stay, as ordinary expenses no longer tied to their transfers.
    op.drop_constraint('transactions_fee_for_id_fkey', 'transactions', type_='foreignkey')
    op.drop_index(op.f('ix_transactions_fee_for_id'), table_name='transactions')
    op.drop_column('transactions', 'fee_for_id')