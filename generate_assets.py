
import os
import json

colors = {
    "DeepNavy": "#0B0E23",
    "NeonOrange": "#FF6B35", 
    "NeonPink": "#E90060",
    "NeonPurple": "#8B5CF6",
    "NeonBlue": "#00C8FF" 
}

base_path = "ios-app/Targets/BombestBeats/Resources/Assets.xcassets"
os.makedirs(base_path, exist_ok=True)

# Main Contents.json
with open(os.path.join(base_path, "Contents.json"), "w") as f:
    json.dump({
        "info": {"author": "xcode", "version": 1}
    }, f, indent=2)

def hex_to_components(hex_color):
    hex_color = hex_color.lstrip('#')
    return {
        "red": str(int(hex_color[0:2], 16) / 255.0),
        "green": str(int(hex_color[2:4], 16) / 255.0),
        "blue": str(int(hex_color[4:6], 16) / 255.0),
        "alpha": "1.000"
    }

for name, hex_code in colors.items():
    color_set_path = os.path.join(base_path, f"{name}.colorset")
    os.makedirs(color_set_path, exist_ok=True)
    
    components = hex_to_components(hex_code)
    
    content = {
        "colors": [
            {
                "color": {
                    "color-space": "srgb",
                    "components": components
                },
                "idiom": "universal"
            }
        ],
        "info": {
            "author": "xcode",
            "version": 1
        }
    }
    
    with open(os.path.join(color_set_path, "Contents.json"), "w") as f:
        json.dump(content, f, indent=2)

# Generate Placeholder AppIcon
app_icon_path = os.path.join(base_path, "AppIcon.appiconset")
os.makedirs(app_icon_path, exist_ok=True)

with open(os.path.join(app_icon_path, "Contents.json"), "w") as f:
    json.dump({
        "images": [
            {
                "idiom": "universal",
                "platform": "ios",
                "size": "1024x1024"
            }
        ],
        "info": {
            "author": "xcode",
            "version": 1
        }
    }, f, indent=2)

print("Assets generated successfully.")
