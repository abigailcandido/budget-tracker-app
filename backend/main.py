from fastapi import FastAPI

app = FastAPI(title="Budget Tracker API")


@app.get("/")
def root():
    return {"message": "Budget Tracker API is running"}


@app.get("/health")
def health_check():
    return {"status": "ok"}