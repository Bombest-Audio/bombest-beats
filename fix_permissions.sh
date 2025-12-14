#!/bin/bash
# Fix permissions for Caddy to access frontend files
chmod o+x /home/thomas
chmod o+x /home/thomas/bombest-beats
chmod o+x /home/thomas/bombest-beats/frontend-build
echo "Permissions fixed! Try https://bom.best/beats now"
