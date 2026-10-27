from setuptools import setup

package_name = "ros2_camera_info_bridge"

setup(
    name=package_name,
    version="0.1.0",
    packages=[package_name],
    entry_points={
        "console_scripts": [
            "android_camera_info = ros2_camera_info_bridge.android_camera_info:main",
        ],
    },
)
