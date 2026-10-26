"""add transaction adjustment links

Revision ID: 2fa3ae14a746
Revises: fb23975e1bb6
Create Date: 2026-10-26 15:03:08.067147

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '2fa3ae14a746'
down_revision: Union[str, Sequence[str], None] = 'fb23975e1bb6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Corrections and deletions now add rows instead of changing or removing
    # them. These columns link each entry's rows together. Existing entries
    # are all originals, so every new column starts empty.
    op.add_column('transactions', sa.Column('reverses_id', sa.Integer(), nullable=True))
    op.add_column('transactions', sa.Column('replaces_id', sa.Integer(), nullable=True))
    op.add_column('transactions', sa.Column('origin_id', sa.Integer(), nullable=True))
    op.create_index(op.f('ix_transactions_origin_id'), 'transactions', ['origin_id'], unique=False)
    op.create_index(op.f('ix_transactions_replaces_id'), 'transactions', ['replaces_id'], unique=False)
    # Unique, so the same entry can never be cancelled twice.
    op.create_index(op.f('ix_transactions_reverses_id'), 'transactions', ['reverses_id'], unique=True)
    # Named the way PostgreSQL would name them, so the downgrade below and any
    # later migration can refer to them.
    op.create_foreign_key(
        'transactions_reverses_id_fkey', 'transactions', 'transactions',
        ['reverses_id'], ['id'], ondelete='SET NULL',
    )
    op.create_foreign_key(
        'transactions_replaces_id_fkey', 'transactions', 'transactions',
        ['replaces_id'], ['id'], ondelete='SET NULL',
    )
    op.create_foreign_key(
        'transactions_origin_id_fkey', 'transactions', 'transactions',
        ['origin_id'], ['id'], ondelete='SET NULL',
    )


def downgrade() -> None:
    """Downgrade schema."""
    # Reversals and corrections only make sense with these links, so going
    # back would leave every corrected entry counted twice. Only downgrade a
    # database where no entry has been corrected or deleted yet.
    op.drop_constraint('transactions_origin_id_fkey', 'transactions', type_='foreignkey')
    op.drop_constraint('transactions_replaces_id_fkey', 'transactions', type_='foreignkey')
    op.drop_constraint('transactions_reverses_id_fkey', 'transactions', type_='foreignkey')
    op.drop_index(op.f('ix_transactions_reverses_id'), table_name='transactions')
    op.drop_index(op.f('ix_transactions_replaces_id'), table_name='transactions')
    op.drop_index(op.f('ix_transactions_origin_id'), table_name='transactions')
    op.drop_column('transactions', 'origin_id')
    op.drop_column('transactions', 'replaces_id')
    op.drop_column('transactions', 'reverses_id')