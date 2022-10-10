# frozen_string_literal: true

desc "Automated admin user creation from env variables"
task "admin:fromenv" => [ :environment ] do
  email = ENV['DISCOURSE_ADMIN_EMAIL']
  password = ENV['DISCOURSE_ADMIN_PASSWORD']
  username = ENV['DISCOURSE_ADMIN_USERNAME']

  if !email || email !~ /@/
    puts "ERROR: Expecting env variable DISCOURSE_ADMIN_EMAIL"
    exit 1
  end

  if !password
    puts "ERROR: Expecting env variable DISCOURSE_ADMIN_PASSWORD"
    exit 1
  end

  user = User.find_by_email(email)
  if user
    puts "Using existing account, reset password!"
    user.password = password
  else
    puts "Creating new account!"
    user = User.new(email: email)
    user.password = password
    user.username = username
  end

  user.grant_admin!
  user.active = true
  if user.trust_level < 1
    user.change_trust_level!(1)
  end
  user.save!
  user.email_tokens.update_all confirmed: true
  user.activate
end