#!/bin/bash

# 定义目标目录
TARGET_DIR="simWorkspace"

# 检查目录是否存在
if [ ! -d "$TARGET_DIR" ]; then
    echo "目录 $TARGET_DIR 不存在，无需删除。"
    exit 0
fi

# 执行删除
rm -r "$TARGET_DIR"
if [ $? -eq 0 ]; then
    echo "删除成功。"
else
    echo "删除失败，请检查权限或路径。"
fi