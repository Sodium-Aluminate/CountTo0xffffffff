# HEX Counter
自动删掉群内除了数数之外的消息。

数数群：[Telegram](https://t.me/CountTo0xffffffff)

## 滥权
* 相比隔壁[乌鸦长老](https://github.com/sorz/ahgroupbot)不会线程堵塞，防止刷屏时删除消息滞后的困扰。
* 禁止修改旧消息，否则在删除消息的同时禁言用户。

## 计数
* 十六进制
* 有用户修改时计数器半回滚防止数数失败。
* 数数可以以 `0x` 开头表示是 16 进制，但即使没有也会被视为 16 进制。
* 如果 bot 停止工作（因为关机或更新），需要白名单用户来重新起头

## Feature
* 允许提前设置白名单，可以发送其他消息和修改消息而不被禁言。
* 但白名单用户数数错误依旧会被删除
* 允许多群组工作，群组列表和白名单支持 username。
