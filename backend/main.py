from fastapi import FastAPI

from app.routers import auth, trackers

app = FastAPI(title="Budget Tracker API")

app.include_router(auth.router)
app.include_router(trackers.router)


@app.get("/")
def root():
    return {"message": "Budget Tracker API is running"}


@app.get("/health")
def health_check():
    return {"status": "ok"}