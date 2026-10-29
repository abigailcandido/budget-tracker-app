"""add tracker month and currency to budget limits

Revision ID: 24ef950fc29a
Revises: 9d88eed160d5
Create Date: 2026-10-29 20:59:52.876602

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '24ef950fc29a'
down_revision: Union[str, Sequence[str], None] = '9d88eed160d5'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Nothing has ever written to this table, so it is empty everywhere. The
    # delete only makes sure the new columns, which every row needs, can be added.
    op.execute("DELETE FROM budget_limits")

    op.add_column('budget_limits', sa.Column('main_tracker_id', sa.Integer(), nullable=False))
    op.add_column('budget_limits', sa.Column('currency', sa.String(length=3), nullable=False))
    op.add_column('budget_limits', sa.Column('starts_on', sa.Date(), nullable=False))
    op.alter_column(
        'budget_limits',
        'monthly_limit',
        existing_type=sa.Numeric(precision=12, scale=2),
        nullable=True,
    )
    op.create_index(
        op.f('ix_budget_limits_main_tracker_id'),
        'budget_limits',
        ['main_tracker_id'],
        unique=False,
    )
    op.create_foreign_key(
        'budget_limits_main_tracker_id_fkey',
        'budget_limits',
        'main_trackers',
        ['main_tracker_id'],
        ['id'],
    )
    op.create_unique_constraint(
        'uq_budget_limits_tracker_category_currency_month',
        'budget_limits',
        ['main_tracker_id', 'category_id', 'currency', 'starts_on'],
    )


def downgrade() -> None:
    """Downgrade schema."""
    # The old table had no tracker, month or currency, so expected amounts
    # cannot be kept going back.
    op.execute("DELETE FROM budget_limits")

    op.drop_constraint(
        'uq_budget_limits_tracker_category_currency_month', 'budget_limits', type_='unique'
    )
    op.drop_constraint('budget_limits_main_tracker_id_fkey', 'budget_limits', type_='foreignkey')
    op.drop_index(op.f('ix_budget_limits_main_tracker_id'), table_name='budget_limits')
    op.alter_column(
        'budget_limits',
        'monthly_limit',
        existing_type=sa.Numeric(precision=12, scale=2),
        nullable=False,
    )
    op.drop_column('budget_limits', 'starts_on')
    op.drop_column('budget_limits', 'currency')
    op.drop_column('budget_limits', 'main_tracker_id')
