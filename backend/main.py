from fastapi import FastAPI

from app.routers import auth, budget, categories, reset_pages, trackers, transactions, wallets

app = FastAPI(title="Budget Tracker API")

app.include_router(auth.router)
app.include_router(trackers.router)
app.include_router(wallets.router)
app.include_router(categories.router)
app.include_router(transactions.router)
app.include_router(budget.router)
app.include_router(reset_pages.router)


@app.get("/")
def root():
    return {"message": "Budget Tracker API is running"}


@app.get("/health")
def health_check():
    return {"status": "ok"}