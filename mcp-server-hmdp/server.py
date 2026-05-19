"""
黑马点评 MCP Server
基于 FastMCP 对接 hm-dianping REST API
支持 stdio（本地调试）和 Streamable HTTP（Docker 部署）两种传输
"""
import os
from fastmcp import FastMCP
import httpx

mcp = FastMCP("hm-dianping")

# 通过环境变量配置后端地址，Docker 部署时指向 hm-dianping 容器
BASE_URL = os.getenv("HMDP_API_URL", "http://localhost:8081")

# 全局 token，登录后自动设置
_token: str | None = None


def _headers() -> dict:
    """带 token 的请求头"""
    h = {"Content-Type": "application/json"}
    if _token:
        h["authorization"] = _token
    return h


def _request(method: str, path: str, **kwargs) -> dict:
    """统一请求封装，带错误处理"""
    try:
        resp = httpx.request(method, f"{BASE_URL}{path}", headers=_headers(), timeout=10, **kwargs)
        resp.raise_for_status()
        return resp.json()
    except httpx.ConnectError:
        return {"success": False, "errorMsg": f"无法连接 hm-dianping 服务 ({BASE_URL})，请确认服务已启动"}
    except httpx.TimeoutException:
        return {"success": False, "errorMsg": "请求超时"}
    except Exception as e:
        return {"success": False, "errorMsg": f"请求异常: {e}"}


def _get(path: str, params: dict | None = None) -> dict:
    """GET 请求封装"""
    return _request("GET", path, params=params)


def _post(path: str, json_data: dict | None = None) -> dict:
    """POST 请求封装"""
    return _request("POST", path, json=json_data)


def _put(path: str) -> dict:
    """PUT 请求封装"""
    return _request("PUT", path)


# ============================================================
# 用户相关工具
# ============================================================

@mcp.tool()
def send_code(phone: str) -> str:
    """发送手机验证码（开发环境验证码会打印在服务端日志中）"""
    result = _post(f"/user/code?phone={phone}")
    if result.get("success"):
        return "验证码发送成功，请查看服务端日志获取验证码"
    return f"发送失败: {result.get('errorMsg')}"


@mcp.tool()
def login(phone: str, code: str) -> str:
    """使用手机号和验证码登录，登录成功后 token 会自动保存"""
    global _token
    result = _post("/user/login", {"phone": phone, "code": code})
    if result.get("success"):
        _token = result["data"]
        return f"登录成功！token 已保存"
    return f"登录失败: {result.get('errorMsg')}"


@mcp.tool()
def get_current_user() -> str:
    """获取当前登录用户的信息"""
    result = _get("/user/me")
    if result.get("success"):
        user = result["data"]
        return f"用户ID: {user['id']}, 昵称: {user.get('nickName', 'N/A')}"
    return f"获取失败（可能未登录）: {result.get('errorMsg')}"


@mcp.tool()
def daily_sign() -> str:
    """每日签到"""
    result = _post("/user/sign")
    if result.get("success"):
        return "签到成功！"
    return f"签到失败: {result.get('errorMsg')}"


# ============================================================
# 博客/点评相关工具
# ============================================================

@mcp.tool()
def publish_blog(shop_id: int, title: str, content: str, images: str = "") -> str:
    """发布一条探店点评博客。images 为图片路径，多张用逗号分隔，可为空"""
    blog_data = {
        "shopId": shop_id,
        "title": title,
        "content": content,
        "images": images,
    }
    result = _post("/blog", blog_data)
    if result.get("success"):
        return f"发布成功！博客ID: {result['data']}"
    return f"发布失败: {result.get('errorMsg')}"


@mcp.tool()
def get_hot_blogs(page: int = 1) -> str:
    """获取热门探店博客列表"""
    result = _get("/blog/hot", {"current": page})
    if not result.get("success"):
        return f"获取失败: {result.get('errorMsg')}"
    blogs = result["data"]
    if not blogs:
        return "暂无热门博客"
    lines = []
    for b in blogs:
        lines.append(f"[ID:{b['id']}] {b.get('title', '无标题')} | 点赞:{b.get('liked', 0)} | 店铺ID:{b.get('shopId')}")
    return "\n".join(lines)


@mcp.tool()
def get_blog_detail(blog_id: int) -> str:
    """获取博客详情"""
    result = _get(f"/blog/{blog_id}")
    if not result.get("success"):
        return f"获取失败: {result.get('errorMsg')}"
    b = result["data"]
    return (
        f"标题: {b.get('title')}\n"
        f"内容: {b.get('content')}\n"
        f"图片: {b.get('images', '无')}\n"
        f"点赞数: {b.get('liked', 0)}\n"
        f"评论数: {b.get('comments', 0)}\n"
        f"店铺ID: {b.get('shopId')}\n"
        f"作者ID: {b.get('userId')}"
    )


@mcp.tool()
def like_blog(blog_id: int) -> str:
    """点赞/取消点赞一篇博客（toggle 切换）"""
    result = _put(f"/blog/like/{blog_id}")
    if result.get("success"):
        return "操作成功"
    return f"操作失败: {result.get('errorMsg')}"


@mcp.tool()
def get_my_blogs(page: int = 1) -> str:
    """获取当前登录用户发布的博客列表"""
    result = _get("/blog/of/me", {"current": page})
    if not result.get("success"):
        return f"获取失败: {result.get('errorMsg')}"
    blogs = result["data"]
    if not blogs:
        return "你还没有发布过博客"
    lines = []
    for b in blogs:
        lines.append(f"[ID:{b['id']}] {b.get('title', '无标题')} | 点赞:{b.get('liked', 0)}")
    return "\n".join(lines)


# ============================================================
# 店铺相关工具
# ============================================================

@mcp.tool()
def get_shop_types() -> str:
    """获取所有店铺分类（美食、KTV、美容美发等）"""
    result = _get("/shop-type/list")
    if not result.get("success"):
        return f"获取失败: {result.get('errorMsg')}"
    types = result["data"]
    lines = []
    for t in types:
        lines.append(f"[ID:{t['id']}] {t['name']}")
    return "\n".join(lines)


@mcp.tool()
def search_shops(keyword: str, page: int = 1) -> str:
    """按关键词搜索店铺"""
    result = _get("/shop/of/name", {"name": keyword, "current": page})
    if not result.get("success"):
        return f"搜索失败: {result.get('errorMsg')}"
    shops = result["data"]
    if not shops:
        return f"未找到包含「{keyword}」的店铺"
    lines = []
    for s in shops:
        price = s.get("avgPrice", 0)
        price_str = f"¥{price / 100:.0f}" if price else "价格未知"
        lines.append(
            f"[ID:{s['id']}] {s['name']} | 评分:{s.get('score', 0) / 10:.1f} | "
            f"均价:{price_str} | 地址:{s.get('address', 'N/A')}"
        )
    return "\n".join(lines)


@mcp.tool()
def get_shops_by_type(type_id: int, page: int = 1) -> str:
    """按分类查询店铺列表。type_id 通过 get_shop_types 获取"""
    result = _get("/shop/of/type", {"typeId": type_id, "current": page})
    if not result.get("success"):
        return f"查询失败: {result.get('errorMsg')}"
    shops = result["data"]
    if not shops:
        return "该分类下暂无店铺"
    lines = []
    for s in shops:
        price = s.get("avgPrice", 0)
        price_str = f"¥{price / 100:.0f}" if price else "价格未知"
        lines.append(
            f"[ID:{s['id']}] {s['name']} | 评分:{s.get('score', 0) / 10:.1f} | "
            f"均价:{price_str} | 地址:{s.get('address', 'N/A')}"
        )
    return "\n".join(lines)


@mcp.tool()
def get_shop_detail(shop_id: int) -> str:
    """获取店铺详情"""
    result = _get(f"/shop/{shop_id}")
    if not result.get("success"):
        return f"获取失败: {result.get('errorMsg')}"
    s = result["data"]
    price = s.get("avgPrice", 0)
    price_str = f"¥{price / 100:.0f}" if price else "价格未知"
    return (
        f"店名: {s.get('name')}\n"
        f"评分: {s.get('score', 0) / 10:.1f}\n"
        f"均价: {price_str}\n"
        f"销量: {s.get('sold', 0)}\n"
        f"评论数: {s.get('comments', 0)}\n"
        f"地址: {s.get('address', 'N/A')}\n"
        f"营业时间: {s.get('openHours', 'N/A')}\n"
        f"图片: {s.get('images', '无')}"
    )


# ============================================================
# 优惠券相关工具
# ============================================================

@mcp.tool()
def get_shop_vouchers(shop_id: int) -> str:
    """查看指定店铺的优惠券列表"""
    result = _get(f"/voucher/list/{shop_id}")
    if not result.get("success"):
        return f"获取失败: {result.get('errorMsg')}"
    vouchers = result["data"]
    if not vouchers:
        return "该店铺暂无优惠券"
    lines = []
    for v in vouchers:
        pay = v.get("payValue", 0)
        actual = v.get("actualValue", 0)
        vtype = "秒杀券" if v.get("type") == 1 else "普通券"
        lines.append(
            f"[ID:{v['id']}] {v.get('title', 'N/A')} ({vtype}) | "
            f"支付:¥{pay / 100:.0f} 抵扣:¥{actual / 100:.0f}"
        )
        if v.get("type") == 1 and v.get("stock") is not None:
            lines[-1] += f" | 库存:{v['stock']}"
    return "\n".join(lines)


# ============================================================
# 入口：根据环境变量选择传输方式
# MCP_TRANSPORT=stdio          → 本地开发（默认）
# MCP_TRANSPORT=sse            → Docker 部署（agent 项目兼容）
# MCP_TRANSPORT=streamable-http → Docker 部署（最新协议）
# ============================================================

if __name__ == "__main__":
    transport = os.getenv("MCP_TRANSPORT", "stdio")
    host = os.getenv("MCP_HOST", "0.0.0.0")
    port = int(os.getenv("MCP_PORT", "9000"))

    if transport == "sse":
        mcp.run(transport="sse", host=host, port=port)
    elif transport == "streamable-http":
        mcp.run(transport="streamable-http", host=host, port=port)
    else:
        mcp.run(transport="stdio")
