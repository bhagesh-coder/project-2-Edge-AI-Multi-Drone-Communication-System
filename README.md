# project-2-Prototype-Model-Using-Federated-Learning-and-Edge-AI

Edge AI Multi-Drone Communication System

An offline, decentralized multi-drone communication system that uses Edge AI, Semantic Communication, and Peer-to-Peer networking to enable efficient target detection and data sharing without internet connectivity. The system processes camera feeds locally on drone-mounted smartphones, detects human targets using a quantized MobileNetV2 TensorFlow Lite model, and transmits only meaningful metadata (GPS coordinates, target IDs, timestamps) instead of raw video streams. This significantly reduces bandwidth consumption and improves reliability in disaster-response and infrastructure-free environments. 

 

-Features

Offline drone-to-base-station communication

Edge AI-based real-time human detection

Semantic data transmission instead of video streaming

Google Nearby Connections API for P2P networking

Low-bandwidth, low-latency communication

Smartphone-based drone implementation

Suitable for search & rescue and disaster management scenarios

🛠 Tech Stack

Android (Kotlin)

TensorFlow Lite

MobileNetV2 (Int8 Quantized)

Google Nearby Connections API

Edge Computing

Peer-to-Peer Networking

-Results

~85% reduction in network channel utilization

Average inference latency of ~42 ms on mobile hardware

Less than 1 KB data transmitted per detection event 

 


Developed as a Bachelor of Technology Major Project by Bhagesh Vispute, Avinash Kumar, and Garvit Panchal.
