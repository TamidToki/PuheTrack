with open("app/build.gradle.kts", "r") as f:
    text = f.read()
text = text.replace("isCrunchPngs = false", "")
text = text.replace("release {", "release {\n            isCrunchPngs = false")
with open("app/build.gradle.kts", "w") as f:
    f.write(text)
