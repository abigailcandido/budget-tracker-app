"""add transfers between wallets

Revision ID: 17b759556f31
Revises: 24ef950fc29a
Create Date: 2026-10-30 09:29:18.519684

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '17b759556f31'
down_revision: Union[str, Sequence[str], None] = '24ef950fc29a'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # "transfer_out" is longer than the ten characters the column allowed.
    op.alter_column(
        'transactions',
        'type',
        existing_type=sa.String(length=10),
        type_=sa.String(length=20),
        existing_nullable=False,
    )
    # No existing entry is a transfer, so the link starts empty everywhere.
    op.add_column('transactions', sa.Column('transfer_out_id', sa.Integer(), nullable=True))
    # Unique: money that leaves one wallet arrives in exactly one other.
    op.create_index(
        op.f('ix_transactions_transfer_out_id'),
        'transactions',
        ['transfer_out_id'],
        unique=True,
    )
    # Named the way PostgreSQL would name it, like the adjustment links.
    op.create_foreign_key(
        'transactions_transfer_out_id_fkey',
        'transactions',
        'transactions',
        ['transfer_out_id'],
        ['id'],
        ondelete='SET NULL',
    )


def downgrade() -> None:
    """Downgrade schema."""
    # Without the link, the two halves of a transfer could no longer be told
    # apart from each other. Only downgrade a database with no transfers in it.
    op.drop_constraint('transactions_transfer_out_id_fkey', 'transactions', type_='foreignkey')
    op.drop_index(op.f('ix_transactions_transfer_out_id'), table_name='transactions')
    op.drop_column('transactions', 'transfer_out_id')
    op.alter_column(
        'transactions',
        'type',
        existing_type=sa.String(length=20),
        type_=sa.String(length=10),
        existing_nullable=False,
    )